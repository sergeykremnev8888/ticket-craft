package ru.ticketcraft.dto;

import java.time.Instant;
import java.util.UUID;

public record EventSummaryResponse(
        UUID id,
        String title,
        String description,
        Instant eventDate,
        String venue) {
}