package ru.ticketcraft.service;

import java.util.List;
import java.util.UUID;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.config.CatalogCacheNames;
import ru.ticketcraft.dto.EventDto;
import ru.ticketcraft.dto.EventSummaryResponse;
import ru.ticketcraft.dto.TicketDto;
import ru.ticketcraft.exception.EventNotFoundException;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.repository.EventRepository;

@Service
public class EventCatalogService {

    private final EventRepository eventRepository;

    public EventCatalogService(EventRepository eventRepository) {

        this.eventRepository = eventRepository;
    }

    /*
     * Production read path.
     *
     * Cache-aside:
     *
     * 1. Spring сначала ищет key "all" в Redis. 2. Cache hit -> PostgreSQL не
     * вызывается. 3. Cache miss -> выполняется метод. 4. Результат сохраняется в
     * Redis.
     */
    @Cacheable(cacheNames = CatalogCacheNames.EVENTS, key = "'all'")
    @Transactional(readOnly = true)
    public List<EventSummaryResponse> getEvents() {
        return eventRepository.findAllSummaries();
    }

    /*
     * Отдельный cache entry для конкретного event.
     *
     * Redis key будет примерно:
     *
     * ticketcraft:catalog:event-by-id::<UUID>
     */
    @Cacheable(cacheNames = CatalogCacheNames.EVENT_BY_ID)
    @Transactional(readOnly = true)
    public EventSummaryResponse getEvent(UUID eventId) {
        return eventRepository.findSummaryById(eventId)
                .orElseThrow(() ->
                        new EventNotFoundException(
                                "Event not found: " + eventId
                        ));
    }

    @Transactional(readOnly = true)
    public List<EventDto> getEventsLazy() {

        return eventRepository.findAll().stream().map(this::convertToDto).toList();
    }

    @Transactional(readOnly = true)
    public List<Event> getEventsWithTicketsGraph() {
        return eventRepository.findAllWithTicketsGraph();
    }

    private EventDto convertToDto(Event event) {

        List<TicketDto> tickets = event.getTickets().stream().map(
                ticket -> new TicketDto(ticket.getId(), ticket.getSeatNumber(), ticket.getPrice(), ticket.getStatus()))
                .toList();

        return new EventDto(event.getId(), event.getTitle(), tickets);
    }
}