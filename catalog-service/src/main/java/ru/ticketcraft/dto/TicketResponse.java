package ru.ticketcraft.dto;

import java.math.BigDecimal;

public record TicketResponse(Long id, String seatNumber, BigDecimal price, boolean available) {
}
