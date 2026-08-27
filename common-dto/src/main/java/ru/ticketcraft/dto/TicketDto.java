package ru.ticketcraft.dto;

import java.math.BigDecimal;

/**
 * DTO информации о билете/месте на мероприятие.
 */
public record TicketDto(
        Long id,
        String seatNumber,
        BigDecimal price,
        boolean isAvailable
) {
}