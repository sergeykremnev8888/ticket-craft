package ru.ticketcraft.payment.service;

import java.time.Instant;
import java.util.List;

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
        List<PaymentOutboxRecord> records = outboxRepository.findUnpublished(100);

        for (PaymentOutboxRecord record : records) {
            Object event = deserializeEvent(record);

            kafkaTemplate.send(kafkaProperties.getResultTopic(), record.orderId().toString(), event)
                    .whenComplete((result, exception) -> {
                        if (exception == null) {
                            outboxRepository.markPublished(record.id(), Instant.now());
                        }
                    });
        }
    }

    private Object deserializeEvent(PaymentOutboxRecord record) {
        return switch (record.eventType()) {
        case PAYMENT_SUCCEEDED_EVENT -> objectMapper.readValue(record.payload(), PaymentSucceededEvent.class);
        case PAYMENT_FAILED_EVENT -> objectMapper.readValue(record.payload(), PaymentFailedEvent.class);
        default -> throw new IllegalArgumentException("Unsupported payment outbox event type: " + record.eventType());
        };
    }
}