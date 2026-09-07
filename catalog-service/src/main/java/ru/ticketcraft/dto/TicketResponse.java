package ru.ticketcraft.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TicketResponse(UUID id, String seatNumber, BigDecimal price, TicketStatus status) {
}
