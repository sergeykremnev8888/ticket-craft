package ru.ticketcraft.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.ticketcraft.dto.EventDto;
import ru.ticketcraft.dto.EventResponse;
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
    
    /**
     * Эндпоинт 1: Демонстрация проблемы N+1.
     * Вызов этого метода сгенерирует в консоли 1 запрос для мероприятий + N запросов для билетов.
     */
    @GetMapping("/events-lazy")
    public ResponseEntity<List<EventDto>> getEventsLazy() {
        return ResponseEntity.ok(eventCatalogService.getEventsLazy());
    }

    /**
     * Эндпоинт 2: Оптимизированный вариант (Решение N+1).
     * Вызов этого метода сгенерирует ровно 1 SQL-запрос с LEFT JOIN.
     */
    @GetMapping("/events-optimized")
    public List<EventResponse> getEventsOptimized() {
    	List<Event> events = eventCatalogService.getEventsWithTicketsGraph();
        return convertToDto(events);
    }

    /**
     * Метод-маппер из JPA Entity в Record DTO.
     * Именно в момент вызова event.getTickets() внутри stream() 
     * Hibernate триггерит ленивую загрузку и идет в базу данных.
     */
    private List<EventResponse> convertToDto(List<Event> events) {
        return events.stream()
            .map(event -> new EventResponse(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getEventDate(),
                event.getTickets().stream() // Здесь происходит ленивый запрос, если данные не были загружены заранее
                    .map(ticket -> new TicketResponse(
                        ticket.getId(),
                        ticket.getSeatNumber(),
                        ticket.getPrice(),
                        ticket.isAvailable()
                    )).toList()
            )).toList();
    }
}
