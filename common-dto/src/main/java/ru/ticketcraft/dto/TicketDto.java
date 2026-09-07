package ru.ticketcraft.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * DTO информации о билете/месте на мероприятие.
 */
public record TicketDto(UUID id, String seatNumber, BigDecimal price, TicketStatus status) {
}