package ru.ticketcraft.service;

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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private static final String PUBLISHER_ID_PREFIX = "catalog-service-";

    private final OutboxClaimService claimService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxPublisherProperties properties;

    private final String publisherId;

    public OutboxPublisher(OutboxClaimService claimService,
            @Qualifier("kafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate, ObjectMapper objectMapper,
            OutboxPublisherProperties properties) {

        this.claimService = claimService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;

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

        int claimed = claimService.claimPending(claimId, now, lockExpiration, now, publisherId, properties.batchSize());

        if (claimed == 0) {
            return;
        }

        List<OutboxEvent> events = claimService.findClaimed(claimId);

        for (OutboxEvent event : events) {
            publishEvent(event, claimId);
        }
    }

    private void publishEvent(OutboxEvent event, UUID claimId) {

        try {

            Object payload = deserialize(event);

            kafkaTemplate.send(event.getTopic(), event.getAggregateId(), payload)
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);

            boolean published = claimService.markPublished(event.getId(), claimId);

            if (!published) {

                log.warn("Outbox event was published to Kafka, " + "but database claim was lost. "
                        + "eventId={}, claimId={}", event.getId(), claimId);
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            handlePublishFailure(event, claimId, e);

        } catch (ExecutionException e) {

            Throwable cause = e.getCause() != null ? e.getCause() : e;

            handlePublishFailure(event, claimId, cause);

        } catch (TimeoutException | RuntimeException e) {

            handlePublishFailure(event, claimId, e);
        }
    }

    private Object deserialize(OutboxEvent event) throws JacksonException {

        OutboxEventType eventType = OutboxEventType.fromValue(event.getEventType());

        return objectMapper.readValue(event.getPayload(), eventType.getPayloadType());
    }

    private void handlePublishFailure(OutboxEvent event, UUID claimId, Throwable cause) {

        Instant nextAttemptAt = Instant.now().plus(properties.retryDelay());

        boolean released = claimService.releaseClaim(event.getId(), claimId, nextAttemptAt);

        if (!released) {

            log.warn(
                    "Failed to release outbox claim because " + "claim is no longer owned. " + "eventId={}, claimId={}",
                    event.getId(), claimId, cause);

            return;
        }

        log.warn("Failed to publish outbox event. " + "eventId={}, claimId={}, " + "nextAttemptAt={}", event.getId(),
                claimId, nextAttemptAt, cause);
    }
}