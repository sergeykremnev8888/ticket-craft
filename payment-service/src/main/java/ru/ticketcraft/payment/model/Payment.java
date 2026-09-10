package ru.ticketcraft.payment.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class Payment {

    private final UUID id;
    private final Long orderId;
    private final Long userId;
    private final BigDecimal amount;
    private final PaymentStatus status;
    private final String messageId;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Payment(UUID id, Long orderId, Long userId, BigDecimal amount, PaymentStatus status, String messageId,
            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.status = status;
        this.messageId = messageId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getMessageId() {
        return messageId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

}