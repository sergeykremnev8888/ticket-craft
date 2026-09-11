package ru.ticketcraft.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.ticketcraft.dto.EventDto;
import ru.ticketcraft.dto.EventResponse;
import ru.ticketcraft.dto.EventSummaryResponse;
import ru.ticketcraft.dto.TicketResponse;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.service.EventCatalogService;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final EventCatalogService eventCatalogService;

    public CatalogController(EventCatalogService eventCatalogService) {

        this.eventCatalogService = eventCatalogService;
    }

    /*
     * Production cached endpoint.
     */
    @GetMapping("/events")
    public ResponseEntity<List<EventSummaryResponse>> getEvents() {

        return ResponseEntity.ok(eventCatalogService.getEvents());
    }

    /*
     * Production cached endpoint.
     */
    @GetMapping("/events/{eventId}")
    public ResponseEntity<EventSummaryResponse> getEvent(@PathVariable("eventId") UUID eventId) {

        return ResponseEntity.ok(eventCatalogService.getEvent(eventId));
    }

    @GetMapping("/events-lazy")
    public ResponseEntity<List<EventDto>> getEventsLazy() {

        return ResponseEntity.ok(eventCatalogService.getEventsLazy());
    }

    @GetMapping("/events-optimized")
    public List<EventResponse> getEventsOptimized() {

        List<Event> events = eventCatalogService.getEventsWithTicketsGraph();

        return convertToDto(events);
    }

    private List<EventResponse> convertToDto(List<Event> events) {

        return events
                .stream().map(
                        event -> new EventResponse(event.getId(), event.getTitle(), event.getDescription(),
                                event.getEventDate(),
                                event.getTickets().stream().map(ticket -> new TicketResponse(ticket.getId(),
                                        ticket.getSeatNumber(), ticket.getPrice(), ticket.getStatus())).toList()))
                .toList();
    }
}