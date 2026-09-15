package ru.ticketcraft.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventResponse(
        UUID id,
        String title,
        String description,
        Instant eventDate,
        String venue,
        List<TicketResponse> tickets) {
}
