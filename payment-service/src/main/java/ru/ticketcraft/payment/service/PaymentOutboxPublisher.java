package ru.ticketcraft.payment.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentRefundedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import ru.ticketcraft.payment.config.PaymentKafkaProperties;
import ru.ticketcraft.payment.config.PaymentOutboxProperties;
import ru.ticketcraft.payment.observability.OutboxMetrics;
import ru.ticketcraft.payment.outbox.PaymentOutboxEventType;
import ru.ticketcraft.payment.outbox.PaymentOutboxRecord;
import ru.ticketcraft.payment.outbox.PaymentOutboxRepository;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(
                       prefix = "ticketcraft.outbox",
                       name = "enabled",
                       havingValue = "true",
                       matchIfMissing = true)
public class PaymentOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutboxPublisher.class);

    private final String instanceId = UUID.randomUUID().toString();

    private final PaymentOutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final PaymentKafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;
    private final PaymentOutboxProperties outboxProperties;
    private final OutboxMetrics outboxMetrics;

    public PaymentOutboxPublisher(PaymentOutboxRepository outboxRepository,
                                  KafkaTemplate<String, Object> kafkaTemplate,
                                  PaymentKafkaProperties kafkaProperties,
                                  PaymentOutboxProperties outboxProperties,
                                  ObjectMapper objectMapper,
                                  OutboxMetrics outboxMetrics) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaProperties = kafkaProperties;
        this.outboxProperties = outboxProperties;
        this.objectMapper = objectMapper;
        this.outboxMetrics = outboxMetrics;
    }

    @Scheduled(fixedDelayString = "${ticketcraft.outbox.publish-delay:1s}",
               scheduler = "paymentOutboxScheduler")
    public void publish() {
        Instant now = Instant.now();
        String claimId = instanceId + ":" + UUID.randomUUID();

        List<PaymentOutboxRecord> records = 
                outboxRepository.claimBatch(outboxProperties.batchSize(),
                                            claimId,
                                            now,
                                            now.minus(outboxProperties.claimLease()));

        List<PendingPublication> publications = new ArrayList<>(records.size());
        for (PaymentOutboxRecord record : records) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            PendingPublication publication = startPublication(record);
            if (publication != null) {
                publications.add(publication);
            }
        }

        for (PendingPublication publication : publications) {
            if (!completePublication(publication)) {
                return;
            }
        }
    }

    private PendingPublication startPublication(PaymentOutboxRecord record) {
        long startedAt = System.nanoTime();
        PaymentOutboxEventType eventType = null;

        try {
            eventType = PaymentOutboxEventType.fromPersistedValue(record.eventType());
            Object event = deserializeEvent(record, eventType);

            CompletableFuture<SendResult<String, Object>> future = 
                    kafkaTemplate.send(kafkaProperties.getResultTopic(), record.orderId().toString(), event);

            return new PendingPublication(record, eventType, future, startedAt);

        } catch (RuntimeException exception) {
            handlePublicationFailure(record, eventType, exception);
            return null;
        }
    }

    private boolean completePublication(PendingPublication publication) {
        try {
            publication.future().get();

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            log.warn("Interrupted while waiting for payment outbox publication "
                    + "[id={}, messageId={}]. Claim will expire.",
                     publication.record().id(),
                     publication.record().messageId());

            return false;

        } catch (ExecutionException exception) {
            handlePublicationFailure(publication.record(), publication.eventType(), exception.getCause());

            return true;
        }

        outboxMetrics.recordPublished(publication.eventType(),
                                      Duration.ofNanos(System.nanoTime() - publication.startedAt()));

        markPublished(publication.record());

        return true;
    }

    private void markPublished(PaymentOutboxRecord record) {
        try {
            int updatedRows = outboxRepository.markPublished(record.id(), record.claimedBy(), Instant.now());
            if (updatedRows != 1) {
                log.warn("Payment outbox event was sent to Kafka, but its publication mark was not updated "
                        + "[id={}, messageId={}, updatedRows={}]", record.id(), record.messageId(), updatedRows);
            }

        } catch (RuntimeException exception) {
            log.error("Payment outbox event was sent to Kafka, "
                    + "but updating its publication mark failed [id={}, messageId={}]. "
                    + "Claim will expire; duplicate delivery is possible.", record.id(), record.messageId(), exception);
        }
    }

    private void handlePublicationFailure(PaymentOutboxRecord record,
                                          PaymentOutboxEventType eventType,
                                          Throwable exception) {
        if (eventType != null) {
            outboxMetrics.recordFailed(eventType);
        }

        log.error("Failed to prepare or send payment outbox event " + "[id={}, messageId={}]",
                  record.id(),
                  record.messageId(),
                  exception);

        releaseClaim(record);
    }

    private void releaseClaim(PaymentOutboxRecord record) {
        try {
            int updatedRows = outboxRepository.releaseClaim(record.id(), record.claimedBy());

            if (updatedRows != 1) {
                log.warn("Payment outbox claim was not released " + "[id={}, messageId={}, updatedRows={}]",
                         record.id(),
                         record.messageId(),
                         updatedRows);
            }

        } catch (RuntimeException exception) {

            log.error("Failed to release payment outbox claim " + "[id={}, messageId={}]. Claim will expire.",
                      record.id(),
                      record.messageId(),
                      exception);
        }
    }

    private Object deserializeEvent(PaymentOutboxRecord record, PaymentOutboxEventType eventType) {
        return switch (eventType) {
        case PAYMENT_SUCCEEDED -> objectMapper.readValue(record.payload(), PaymentSucceededEvent.class);
        case PAYMENT_FAILED -> objectMapper.readValue(record.payload(), PaymentFailedEvent.class);
        case PAYMENT_REFUNDED -> objectMapper.readValue(record.payload(), PaymentRefundedEvent.class);
        };
    }

    private record PendingPublication(PaymentOutboxRecord record,
                                      PaymentOutboxEventType eventType,
                                      CompletableFuture<SendResult<String, Object>> future,
                                      long startedAt) {
    }
}