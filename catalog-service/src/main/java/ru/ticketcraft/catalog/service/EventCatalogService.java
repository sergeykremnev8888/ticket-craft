package ru.ticketcraft.catalog.service;

import java.util.List;
import java.util.UUID;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.catalog.config.CatalogCacheNames;
import ru.ticketcraft.catalog.dto.EventResponse;
import ru.ticketcraft.catalog.dto.EventSummaryResponse;
import ru.ticketcraft.catalog.dto.TicketResponse;
import ru.ticketcraft.catalog.exception.EventNotFoundException;
import ru.ticketcraft.catalog.model.Event;
import ru.ticketcraft.catalog.repository.EventRepository;

@Service
public class EventCatalogService {

    private final EventRepository eventRepository;

    public EventCatalogService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Cacheable(cacheNames = CatalogCacheNames.EVENTS, key = "'all'")
    @Transactional(readOnly = true)
    public List<EventSummaryResponse> getEvents() {
        return eventRepository.findAllSummaries();
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(UUID eventId) {
        Event event = eventRepository.findByIdWithTickets(eventId)
                .orElseThrow(() -> new EventNotFoundException("Event not found: " + eventId));

        List<TicketResponse> tickets = event.getTickets().stream()
                .map(ticket -> new TicketResponse(ticket.getId(), ticket.getSeatNumber(), ticket.getPrice(), ticket.getStatus()))
                .toList();

        return new EventResponse(event.getId(), event.getTitle(), event.getDescription(), event.getEventDate(),
                event.getVenue(), tickets);
    }
}
