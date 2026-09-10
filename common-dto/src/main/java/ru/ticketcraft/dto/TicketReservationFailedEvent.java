package ru.ticketcraft.dto;

import java.time.Instant;
import java.util.UUID;

public record TicketReservationFailedEvent(String messageId, Long orderId, UUID reservationId, UUID ticketId,
        String reason, Instant occurredAt) {
}