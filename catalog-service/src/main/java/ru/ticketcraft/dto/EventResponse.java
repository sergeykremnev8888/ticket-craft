package ru.ticketcraft.dto;

import java.time.Instant;
import java.util.List;

public record EventResponse(Long id, String title, String description, Instant eventDate,
		List<TicketResponse> tickets) {
}
