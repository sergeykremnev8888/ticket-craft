package ru.ticketcraft.dto;

import java.util.List;

/**
 * DTO мероприятия с вложенным списком доступных билетов.
 */
public record EventDto(Long id, String title, List<TicketDto> tickets) {
	// Удобный компактный конструктор для защиты от null в списке билетов
	public EventDto {
		tickets = (tickets != null) ? List.copyOf(tickets) : List.of();
	}
}
