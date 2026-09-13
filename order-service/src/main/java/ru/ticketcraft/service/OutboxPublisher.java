package ru.ticketcraft.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import ru.ticketcraft.config.OutboxPublisherProperties;
import ru.ticketcraft.model.OutboxEvent;
import ru.ticketcraft.model.OutboxEventType;
import ru.ticketcraft.observability.OutboxMetrics;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private static final String PUBLISHER_ID_PREFIX = "order-service-";

    private final OutboxClaimService claimService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxPublisherProperties properties;
    private final OutboxMetrics outboxMetrics;

    private final String publisherId;

    public OutboxPublisher(OutboxClaimService claimService, @Qualifier("kafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper, OutboxPublisherProperties properties, OutboxMetrics outboxMetrics) {
        this.claimService = claimService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.outboxMetrics = outboxMetrics;
        this.publisherId = PUBLISHER_ID_PREFIX + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${ticketcraft.outbox.publisher.fixed-delay:1s}")
    public void publishPendingEvents() {
        if (!properties.enabled()) {
            return;
        }

        UUID claimId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant lockExpiration = now.minus(properties.lockDuration());
        Instant lockedAt = now;

        int claimed = claimService.claimPending(claimId, now, lockExpiration, lockedAt, publisherId,
                properties.batchSize());

        if (claimed == 0) {
            return;
        }

        log.debug("Claimed {} outbox events, claimId={}", claimed, claimId);

        List<OutboxEvent> events = claimService.findClaimed(claimId);

        for (OutboxEvent event : events) {
            publishEvent(event, claimId);
        }
    }

    private void publishEvent(OutboxEvent event, UUID claimId) {
        long startedAt = System.nanoTime();
        OutboxEventType eventType = null;

        try {
            eventType = OutboxEventType.fromValue(event.getEventType());
            Object payload = deserialize(event, eventType);

            kafkaTemplate.send(event.getTopic(), event.getAggregateId(), payload)
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);

            boolean published = claimService.markPublished(event.getId(), claimId);

            if (!published) {
                log.warn(
                        "Outbox event was published to Kafka, but database claim was lost. " + "eventId={}, claimId={}",
                        event.getId(), claimId);

                return;
            }

            outboxMetrics.recordPublished(eventType, Duration.ofNanos(System.nanoTime() - startedAt));

            log.debug("Published outbox event. eventId={}, eventType={}, topic={}, " + "aggregateId={}, claimId={}",
                    event.getId(), event.getEventType(), event.getTopic(), event.getAggregateId(), claimId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            handlePublishFailure(event, claimId, eventType, e);

        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;

            handlePublishFailure(event, claimId, eventType, cause);

        } catch (TimeoutException | RuntimeException e) {
            handlePublishFailure(event, claimId, eventType, e);
        }
    }

    private Object deserialize(OutboxEvent event, OutboxEventType eventType) throws JacksonException {
        return objectMapper.readValue(event.getPayload(), eventType.getPayloadType());
    }

    private void handlePublishFailure(OutboxEvent event, UUID claimId, OutboxEventType eventType, Throwable cause) {
        if (eventType != null) {
            outboxMetrics.recordFailed(eventType);
        }
        Instant nextAttemptAt = Instant.now().plus(properties.retryDelay());

        boolean released = claimService.releaseClaim(event.getId(), claimId, nextAttemptAt);

        if (!released) {
            log.warn("Failed to release outbox claim because claim is no longer owned. eventId={}, claimId={}",
                    event.getId(), claimId, cause);

            return;
        }

        log.warn("Failed to publish outbox event. eventId={}, claimId={}, nextAttemptAt={}", event.getId(), claimId,
                nextAttemptAt, cause);
    }
}