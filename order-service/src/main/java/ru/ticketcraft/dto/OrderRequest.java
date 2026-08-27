package ru.ticketcraft.dto;

import java.math.BigDecimal;

/**
 * Входящий запрос от клиента на бронирование и покупку билета
 */
public record OrderRequest(
    Long userId,
    Long eventId,
    Long ticketId,
    BigDecimal price
) {}
