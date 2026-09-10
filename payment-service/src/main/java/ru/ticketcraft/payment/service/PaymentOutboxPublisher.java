package ru.ticketcraft.payment.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import ru.ticketcraft.payment.config.PaymentKafkaProperties;
import ru.ticketcraft.payment.outbox.PaymentOutboxRecord;
import ru.ticketcraft.payment.outbox.PaymentOutboxRepository;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentOutboxPublisher {

    private static final String PAYMENT_SUCCEEDED_EVENT = "PaymentSucceededEvent";

    private static final String PAYMENT_FAILED_EVENT = "PaymentFailedEvent";

    private static final int BATCH_SIZE = 100;

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);

    private final String instanceId = UUID.randomUUID().toString();

    private final PaymentOutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final PaymentKafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;

    public PaymentOutboxPublisher(PaymentOutboxRepository outboxRepository, KafkaTemplate<String, Object> kafkaTemplate,
            PaymentKafkaProperties kafkaProperties, ObjectMapper objectMapper) {

        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaProperties = kafkaProperties;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "1000")
    public void publish() {
        Instant now = Instant.now();
        Instant claimExpiredBefore = now.minus(CLAIM_LEASE);

        List<PaymentOutboxRecord> records = outboxRepository.claimBatch(BATCH_SIZE, instanceId, now,
                claimExpiredBefore);

        for (PaymentOutboxRecord record : records) {
            publishRecord(record);
        }
    }

    private void publishRecord(PaymentOutboxRecord record) {
        Object event = deserializeEvent(record);

        kafkaTemplate.send(kafkaProperties.getResultTopic(), record.orderId().toString(), event)
                .whenComplete((result, exception) -> {
                    if (exception == null) {
                        outboxRepository.markPublished(record.id(), instanceId, Instant.now());
                    } else {
                        outboxRepository.releaseClaim(record.id(), instanceId);
                    }
                });
    }

    private Object deserializeEvent(PaymentOutboxRecord record) {
        return switch (record.eventType()) {
        case PAYMENT_SUCCEEDED_EVENT -> objectMapper.readValue(record.payload(), PaymentSucceededEvent.class);
        case PAYMENT_FAILED_EVENT -> objectMapper.readValue(record.payload(), PaymentFailedEvent.class);
        default -> throw new IllegalArgumentException("Unsupported payment outbox event type: " + record.eventType());
        };
    }
}