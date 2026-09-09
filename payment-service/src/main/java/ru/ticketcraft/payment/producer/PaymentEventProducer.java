package ru.ticketcraft.payment.producer;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;

@Component
public class PaymentEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(PaymentSucceededEvent event) {
        kafkaTemplate.send("payment-results", event.messageId(), event);
    }

    public void publish(PaymentFailedEvent event) {
        kafkaTemplate.send("payment-results", event.messageId(), event);
    }
}
