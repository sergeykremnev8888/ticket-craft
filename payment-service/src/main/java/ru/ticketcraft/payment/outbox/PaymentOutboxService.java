package ru.ticketcraft.payment.outbox;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import tools.jackson.databind.ObjectMapper;

@Service
public class PaymentOutboxService {

    private static final String PAYMENT_SUCCEEDED_EVENT = "PaymentSucceededEvent";

    private static final String PAYMENT_FAILED_EVENT = "PaymentFailedEvent";

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final ObjectMapper objectMapper;

    public PaymentOutboxService(PaymentOutboxRepository paymentOutboxRepository, ObjectMapper objectMapper) {

        this.paymentOutboxRepository = paymentOutboxRepository;
        this.objectMapper = objectMapper;
    }

    public void addSucceededEvent(PaymentSucceededEvent event) {
        addEvent(event.messageId(), event.orderId(), PAYMENT_SUCCEEDED_EVENT, event);
    }

    public void addFailedEvent(PaymentFailedEvent event) {
        addEvent(event.messageId(), event.orderId(), PAYMENT_FAILED_EVENT, event);
    }

    private void addEvent(String messageId, Long orderId, String eventType, Object event) {

        String payload = objectMapper.writeValueAsString(event);

        paymentOutboxRepository.insertIfAbsent(UUID.randomUUID(), messageId, orderId, eventType, payload,
                Instant.now());
    }
}