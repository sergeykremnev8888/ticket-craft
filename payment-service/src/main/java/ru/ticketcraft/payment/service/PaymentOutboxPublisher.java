package ru.ticketcraft.payment.service;

import java.time.Instant;
import java.util.List;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ru.ticketcraft.payment.config.PaymentKafkaProperties;
import ru.ticketcraft.payment.outbox.PaymentOutboxRecord;
import ru.ticketcraft.payment.outbox.PaymentOutboxRepository;

@Component
public class PaymentOutboxPublisher {

    private final PaymentOutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final PaymentKafkaProperties kafkaProperties;

    public PaymentOutboxPublisher(PaymentOutboxRepository outboxRepository, KafkaTemplate<String, Object> kafkaTemplate,
            PaymentKafkaProperties kafkaProperties) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaProperties = kafkaProperties;
    }

    @Scheduled(fixedDelayString = "1000")
    public void publish() {
        List<PaymentOutboxRecord> records = outboxRepository.findUnpublished(100);

        for (PaymentOutboxRecord record : records) {
            kafkaTemplate.send(kafkaProperties.getResultTopic(), record.messageId(), record.payload())
                    .whenComplete((result, exception) -> {
                        if (exception == null) {
                            outboxRepository.markPublished(record.id(), Instant.now());
                        }
                    });
        }
    }
}