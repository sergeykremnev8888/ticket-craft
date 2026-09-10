package ru.ticketcraft.payment.outbox;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import tools.jackson.databind.ObjectMapper;

@Service
public class PaymentOutboxService {

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final ObjectMapper objectMapper;

    public PaymentOutboxService(PaymentOutboxRepository paymentOutboxRepository, ObjectMapper objectMapper) {

        this.paymentOutboxRepository = paymentOutboxRepository;
        this.objectMapper = objectMapper;
    }

    public void addSucceededEvent(PaymentSucceededEvent event) {
        addEvent(event.messageId(), event.orderId(), PaymentOutboxEventType.PAYMENT_SUCCEEDED, event);
    }

    public void addFailedEvent(PaymentFailedEvent event) {
        addEvent(event.messageId(), event.orderId(), PaymentOutboxEventType.PAYMENT_FAILED, event);
    }

    private void addEvent(String messageId, Long orderId, PaymentOutboxEventType eventType, Object event) {
        String payload = objectMapper.writeValueAsString(event);

        paymentOutboxRepository.insertIfAbsent(UUID.randomUUID(), messageId, orderId, eventType.getPersistedValue(),
                payload, Instant.now());
    }
}