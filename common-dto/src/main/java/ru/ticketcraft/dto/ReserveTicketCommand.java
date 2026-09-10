package ru.ticketcraft.dto;

import java.time.Instant;
import java.util.UUID;

public record ReserveTicketCommand(String messageId, Long orderId, UUID reservationId, UUID ticketId, Long userId,
        Instant occurredAt) {
}