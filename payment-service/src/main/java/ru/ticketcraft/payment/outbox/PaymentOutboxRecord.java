package ru.ticketcraft.payment.outbox;

import java.time.Instant;
import java.util.UUID;

public record PaymentOutboxRecord(UUID id, String messageId, Long orderId, String eventType, String payload,
        Instant createdAt) {
}