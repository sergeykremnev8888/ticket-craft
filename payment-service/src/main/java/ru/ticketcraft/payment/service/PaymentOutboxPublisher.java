package ru.ticketcraft.payment.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import ru.ticketcraft.payment.config.PaymentKafkaProperties;
import ru.ticketcraft.payment.config.PaymentOutboxProperties;
import ru.ticketcraft.payment.outbox.PaymentOutboxEventType;
import ru.ticketcraft.payment.outbox.PaymentOutboxRecord;
import ru.ticketcraft.payment.outbox.PaymentOutboxRepository;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutboxPublisher.class);

    private final String instanceId = UUID.randomUUID().toString();

    private final PaymentOutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final PaymentKafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;
    private final PaymentOutboxProperties outboxProperties;

    public PaymentOutboxPublisher(PaymentOutboxRepository outboxRepository, KafkaTemplate<String, Object> kafkaTemplate,
            PaymentKafkaProperties kafkaProperties, PaymentOutboxProperties outboxProperties,
            ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaProperties = kafkaProperties;
        this.outboxProperties = outboxProperties;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${ticketcraft.outbox.publish-delay:1s}")
    public void publish() {
        Instant now = Instant.now();

        List<PaymentOutboxRecord> records = outboxRepository.claimBatch(outboxProperties.getBatchSize(), instanceId,
                now, now.minus(outboxProperties.getClaimLease()));

        for (PaymentOutboxRecord record : records) {
            publishRecord(record);
        }
    }

    private void publishRecord(PaymentOutboxRecord record) {
        try {
            Object event = deserializeEvent(record);

            kafkaTemplate.send(kafkaProperties.getResultTopic(), record.orderId().toString(), event)
                    .whenComplete((result, exception) -> {
                        if (exception == null) {
                            int updatedRows = outboxRepository.markPublished(record.id(), instanceId, Instant.now());

                            if (updatedRows != 1) {
                                log.warn("Outbox record was published but could not be marked as published "
                                        + "[id={}, messageId={}]", record.id(), record.messageId());
                            }

                            return;
                        }

                        log.error("Failed to publish payment outbox event " + "[id={}, messageId={}]", record.id(),
                                record.messageId(), exception);

                        outboxRepository.releaseClaim(record.id(), instanceId);
                    });
        } catch (RuntimeException exception) {
            log.error("Failed to prepare payment outbox event " + "[id={}, messageId={}]", record.id(),
                    record.messageId(), exception);

            outboxRepository.releaseClaim(record.id(), instanceId);
        }
    }

    private Object deserializeEvent(PaymentOutboxRecord record) {
        PaymentOutboxEventType eventType = PaymentOutboxEventType.fromPersistedValue(record.eventType());

        return switch (eventType) {
        case PAYMENT_SUCCEEDED -> objectMapper.readValue(record.payload(), PaymentSucceededEvent.class);

        case PAYMENT_FAILED -> objectMapper.readValue(record.payload(), PaymentFailedEvent.class);
        };
    }
}