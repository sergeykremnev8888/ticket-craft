package ru.ticketcraft.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Событие изменения статуса заказа билетов для передачи через Apache Kafka.
 * Класс полностью иммутабелен (содержит private final поля без сеттеров).
 */
public final class OrderEvent {

	private final String messageId;
	private final Long orderId;
	private final Long userId;
	private final Long eventId;
	private final List<Long> ticketIds;
	private final BigDecimal totalPrice;
	private final OrderState state;
	private final Instant createdAt;

	/**
	 * Конструктор для Jackson 3 и ручного создания через оператор new.
	 */
	@JsonCreator
	public OrderEvent(@JsonProperty("messageId") String messageId, @JsonProperty("orderId") Long orderId,
			@JsonProperty("userId") Long userId, @JsonProperty("eventId") Long eventId,
			@JsonProperty("ticketIds") List<Long> ticketIds, @JsonProperty("totalPrice") BigDecimal totalPrice,
			@JsonProperty("state") OrderState state, @JsonProperty("createdAt") Instant createdAt) {
		this.messageId = messageId;
		this.orderId = orderId;
		this.userId = userId;
		this.eventId = eventId;
		this.ticketIds = ticketIds;
		this.totalPrice = totalPrice;
		this.state = state;
		this.createdAt = createdAt;
	}

	public String getMessageId() {
		return messageId;
	}

	public Long getOrderId() {
		return orderId;
	}

	public Long getUserId() {
		return userId;
	}

	public Long getEventId() {
		return eventId;
	}

	public List<Long> getTicketIds() {
		return ticketIds;
	}

	public BigDecimal getTotalPrice() {
		return totalPrice;
	}

	public OrderState getState() {
		return state;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
