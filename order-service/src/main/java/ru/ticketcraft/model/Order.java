package ru.ticketcraft.model;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import ru.ticketcraft.dto.OrderState;

@Table("orders") // Аннотация из Spring Data Relational
public class Order {

	@Id // Аннотация Spring Data (НЕ jakarta.persistence)
	private Long id;

	@Column("user_id")
	private Long userId;

	@Column("event_id")
	private Long eventId;

	@Column("total_price")
	private BigDecimal totalPrice;

	@Column("status")
	private OrderState status;

	@Column("created_at")
	private Instant createdAt;

	// Конструкторы
	public Order() {
	}

	public Order(Long id, Long userId, Long eventId, BigDecimal totalPrice, OrderState status, Instant createdAt) {
		this.id = id;
		this.userId = userId;
		this.eventId = eventId;
		this.totalPrice = totalPrice;
		this.status = status;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public Long getEventId() {
		return eventId;
	}

	public void setEventId(Long eventId) {
		this.eventId = eventId;
	}

	public BigDecimal getTotalPrice() {
		return totalPrice;
	}

	public void setTotalPrice(BigDecimal totalPrice) {
		this.totalPrice = totalPrice;
	}

	public OrderState getStatus() {
		return status;
	}

	public void setStatus(OrderState status) {
		this.status = status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}