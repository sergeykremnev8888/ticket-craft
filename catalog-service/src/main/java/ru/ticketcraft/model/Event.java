package ru.ticketcraft.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "events")
public class Event {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "title", nullable = false)
	private String title; // Название, например "Спектакль 'Гамлет'"

	@Column(name = "description")
	private String description;

	@Column(name = "event_date", nullable = false)
	private Instant eventDate; // Дата и время проведения в UTC

	/**
	 * Одно мероприятие имеет много билетов. Параметр fetch = FetchType.LAZY
	 * означает, что список билетов не будет загружаться из БД автоматически, а
	 * только при первом вызове метода event.getTickets(). ИМЕННО ЭТА НАСТРОЙКА в
	 * сочетании с циклом порождает классическую проблему N+1.
	 */
	@OneToMany(mappedBy = "event", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
	private List<Ticket> tickets = new ArrayList<>();

	// Явный конструктор без параметров (обязателен для JPA)
	public Event() {
	}

	// Явный конструктор со всеми параметрами
	public Event(Long id, String title, String description, Instant eventDate, List<Ticket> tickets) {
		this.id = id;
		this.title = title;
		this.description = description;
		this.eventDate = eventDate;
		this.tickets = tickets;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Instant getEventDate() {
		return eventDate;
	}

	public void setEventDate(Instant eventDate) {
		this.eventDate = eventDate;
	}

	public List<Ticket> getTickets() {
		return tickets;
	}

	public void setTickets(List<Ticket> tickets) {
		this.tickets = tickets;
	}
}
