package ru.ticketcraft.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;

import ru.ticketcraft.dto.TicketStatus;

public record TicketResponse(UUID id, String seatNumber, BigDecimal price, TicketStatus status) {
}
