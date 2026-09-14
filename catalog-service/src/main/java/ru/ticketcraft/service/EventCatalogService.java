package ru.ticketcraft.service;

import java.util.List;
import java.util.UUID;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.config.CatalogCacheNames;
import ru.ticketcraft.dto.EventResponse;
import ru.ticketcraft.dto.EventSummaryResponse;
import ru.ticketcraft.dto.TicketResponse;
import ru.ticketcraft.exception.EventNotFoundException;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.repository.EventRepository;

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
