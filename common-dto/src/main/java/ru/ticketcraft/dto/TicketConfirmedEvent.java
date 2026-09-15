package ru.ticketcraft.dto;

import java.time.Instant;
import java.util.UUID;

public record TicketConfirmedEvent(
        String messageId,
        Long orderId,
        UUID reservationId,
        UUID ticketId,
        Instant occurredAt) {
}