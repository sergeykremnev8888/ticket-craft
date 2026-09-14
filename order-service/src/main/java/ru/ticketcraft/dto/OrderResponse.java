package ru.ticketcraft.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        Long id,
        Long userId,
        UUID eventId,
        UUID ticketId,
        BigDecimal totalPrice,
        OrderState status,
        Instant createdAt) {
}
