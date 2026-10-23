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

        for (int index = 0; index < properties.batchSize(); index++) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            UUID claimId = UUID.randomUUID();
            Instant now = Instant.now();

            int claimed = claimService.claimPending(claimId,
                                                    now,
                                                    now.minus(properties.lockDuration()),
                                                    now,
                                                    publisherId,
                                                    1);

            if (claimed == 0) {
                return;
            }

            List<OutboxEvent> events = claimService.findClaimed(claimId);
            for (OutboxEvent event : events) {

                if (Thread.currentThread().isInterrupted()) {
                    return;
                }

                if (!publishEvent(event, claimId)) {
                    return;
                }
            }
        }
    }

    private boolean publishEvent(OutboxEvent event, UUID claimId) {
        long startedAt = System.nanoTime();
        OutboxEventType eventType = null;

        try {
            eventType = OutboxEventType.fromValue(event.getEventType());

            Object payload = deserialize(event, eventType);

            kafkaTemplate.send(event.getTopic(), event.getAggregateId(), payload)
                         .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            log.warn("Outbox publication interrupted [eventId={}, claimId={}]. Claim will expire.",
                     event.getId(),
                     claimId);

            return false;

        } catch (TimeoutException exception) {
            if (eventType != null) {
                outboxMetrics.recordFailed(eventType);
            }

            log.warn("Timed out waiting for Kafka acknowledgment [eventId={}, claimId={}]. "
                    + "Delivery outcome is unknown; claim will expire.", event.getId(), claimId, exception);

            return true;

        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            handlePublishFailure(event, claimId, eventType, cause);

            return true;

        } catch (RuntimeException exception) {
            handlePublishFailure(event, claimId, eventType, exception);

            return true;
        }

        markPublished(event, claimId, eventType, startedAt);

        return true;
    }

    private void markPublished(OutboxEvent event, UUID claimId, OutboxEventType eventType, long startedAt) {
        try {
            boolean published = claimService.markPublished(event.getId(), claimId);

            if (!published) {
                log.warn("Outbox event was sent to Kafka, but its database claim was lost "
                        + "[eventId={}, claimId={}]", event.getId(), claimId);
                return;
            }

            outboxMetrics.recordPublished(eventType, Duration.ofNanos(System.nanoTime() - startedAt));

            log.debug("Published outbox event [eventId={}, eventType={}, topic={}, claimId={}]",
                      event.getId(),
                      event.getEventType(),
                      event.getTopic(),
                      claimId);

        } catch (RuntimeException exception) {
            log.error("Outbox event was sent to Kafka, "
                    + "but recording publication failed [eventId={}, claimId={}]. "
                    + "Claim will expire; duplicate delivery is possible.", event.getId(), claimId, exception);
        }
    }

    private Object deserialize(OutboxEvent event, OutboxEventType eventType) throws JacksonException {
        return objectMapper.readValue(event.getPayload(), eventType.getPayloadType());
    }

    private void handlePublishFailure(OutboxEvent event, UUID claimId, OutboxEventType eventType, Throwable cause) {
        if (eventType != null) {
            outboxMetrics.recordFailed(eventType);
        }

        log.warn("Failed to publish outbox event [eventId={}, claimId={}]", event.getId(), claimId, cause);

        try {
            Instant nextAttemptAt = Instant.now().plus(properties.retryDelay());

            boolean released = claimService.releaseClaim(event.getId(), claimId, nextAttemptAt);

            if (!released) {
                log.warn("Outbox claim was not released because ownership changed [eventId={}, claimId={}]",
                         event.getId(),
                         claimId);
            }

        } catch (RuntimeException exception) {
            log.error("Failed to release outbox claim [eventId={}, claimId={}]. Claim will expire.",
                      event.getId(),
                      claimId,
                      exception);
        }
    }
}