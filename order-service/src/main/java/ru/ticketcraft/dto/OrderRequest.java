package ru.ticketcraft.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Входящий запрос от клиента на бронирование и покупку билета
 */
public record OrderRequest(
        @NotNull
        Long userId,

        @NotNull
        UUID eventId,

        @NotNull
        UUID ticketId,

        @NotNull
        @Positive
        BigDecimal price) {
}
