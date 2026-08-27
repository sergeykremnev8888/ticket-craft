package ru.ticketcraft.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.dto.EventDto;
import ru.ticketcraft.dto.TicketDto;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.repository.EventRepository;

@Service
public class EventCatalogService {

	private final EventRepository eventRepository;

	public EventCatalogService(EventRepository eventRepository) {
		this.eventRepository = eventRepository;
	}

	/**
	 * ДЕМОНСТРАЦИЯ ПРОБЛЕМЫ N+1: Сессия открыта благодаря @Transactional(readOnly =
	 * true). При обходе event.getTickets() Hibernate выполнит N дополнительных
	 * SELECT-запросов.
	 */
	@Transactional(readOnly = true)
	public List<EventDto> getEventsLazy() {
		return eventRepository.findAll().stream().map(this::convertToDto).toList();
	}

	public List<Event> getEventsWithTicketsGraph() {
		return eventRepository.findAllWithTicketsGraph();
	}

	private EventDto convertToDto(Event event) {
		List<TicketDto> tickets = event.getTickets().stream()
				.map(t -> new TicketDto(t.getId(), t.getSeatNumber(), t.getPrice(), t.isAvailable())).toList();

		return new EventDto(event.getId(), event.getTitle(), tickets);
	}

}
