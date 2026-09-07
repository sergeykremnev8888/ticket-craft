package ru.ticketcraft.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Входящий запрос от клиента на бронирование и покупку билета
 */
public record OrderRequest(Long userId, UUID eventId, UUID ticketId, BigDecimal price) {
}
