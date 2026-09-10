package ru.ticketcraft.payment.outbox;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PaymentOutboxService {

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public PaymentOutboxService(PaymentOutboxRepository paymentOutboxRepository) {
        this.paymentOutboxRepository = paymentOutboxRepository;
    }

    public void addSucceededEvent(PaymentSucceededEvent event) {
        addEvent(event.messageId(), event.orderId(), "PaymentSucceededEvent", event);
    }

    public void addFailedEvent(PaymentFailedEvent event) {
        addEvent(event.messageId(), event.orderId(), "PaymentFailedEvent", event);
    }

    private void addEvent(String messageId, Long orderId, String eventType, Object event) {
        String payload = objectMapper.writeValueAsString(event);

        paymentOutboxRepository.insertIfAbsent(UUID.randomUUID(), messageId, orderId, eventType, payload,
                Instant.now());
    }
}