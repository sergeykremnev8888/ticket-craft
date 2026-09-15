package ru.ticketcraft.dto;

import java.time.Instant;
import java.util.UUID;

public record ConfirmTicketCommand(
        String messageId,
        Long orderId,
        UUID reservationId,
        UUID ticketId,
        Instant occurredAt) {
}