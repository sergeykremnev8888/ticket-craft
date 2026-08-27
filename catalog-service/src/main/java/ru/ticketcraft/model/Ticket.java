package ru.ticketcraft.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "tickets")
public class Ticket {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "seat_number", nullable = false)
	private String seatNumber; // Номер места, например "Ряд 5, Место 12"

	@Column(name = "price", nullable = false)
	private BigDecimal price;

	@Column(name = "is_available", nullable = false)
	private boolean available = true; // Свободен ли билет для покупки

	/**
	 * Многие билеты относятся к одному мероприятию. На собеседованиях Senior уровня
	 * всегда ценят использование FetchType.LAZY для @ManyToOne, чтобы при загрузке
	 * билета из базы не тащить за собой тяжелый объект мероприятия без
	 * необходимости.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "event_id", nullable = false)
	private Event event;

	public Ticket() {
	}

	public Ticket(Long id, String seatNumber, BigDecimal price, boolean available, Event event) {
		this.id = id;
		this.seatNumber = seatNumber;
		this.price = price;
		this.available = available;
		this.event = event;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getSeatNumber() {
		return seatNumber;
	}

	public void setSeatNumber(String seatNumber) {
		this.seatNumber = seatNumber;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public void setPrice(BigDecimal price) {
		this.price = price;
	}

	public boolean isAvailable() {
		return available;
	}

	public void setAvailable(boolean available) {
		this.available = available;
	}

	public Event getEvent() {
		return event;
	}

	public void setEvent(Event event) {
		this.event = event;
	}
}
