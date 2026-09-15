package ru.ticketcraft.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.ticketcraft.dto.EventResponse;
import ru.ticketcraft.dto.EventSummaryResponse;
import ru.ticketcraft.service.EventCatalogService;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final EventCatalogService eventCatalogService;

    public CatalogController(EventCatalogService eventCatalogService) {
        this.eventCatalogService = eventCatalogService;
    }

    @GetMapping("/events")
    public ResponseEntity<List<EventSummaryResponse>> getEvents() {
        return ResponseEntity.ok(eventCatalogService.getEvents());
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable("eventId") UUID eventId) {
        return ResponseEntity.ok(eventCatalogService.getEvent(eventId));
    }
}
