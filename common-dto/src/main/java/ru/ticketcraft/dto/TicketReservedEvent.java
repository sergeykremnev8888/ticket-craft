package ru.ticketcraft.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative reservation result emitted by catalog-service.
 *
 * eventId and price come from catalog_db and must be used by downstream services
 * instead of trusting client supplied order data.
 */
public record TicketReservedEvent(
        String messageId,
        Long orderId,
        UUID reservationId,
        UUID ticketId,
        UUID eventId,
        BigDecimal price,
        Instant occurredAt) {
}
