package ru.ticketcraft.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column("idempotency_key")
    private String idempotencyKey;

    @Column("user_id")
    private Long userId;

    @Column("request_hash")
    private String requestHash;

    @Column("order_id")
    private Long orderId;

    @Column("status")
    private IdempotencyStatus status;

    @Column("created_at")
    private Instant createdAt;

    public IdempotencyKey() {
    }

    public IdempotencyKey(String idempotencyKey, Long userId, String requestHash, Long orderId,
            IdempotencyStatus status, Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.requestHash = requestHash;
        this.orderId = orderId;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public void setStatus(IdempotencyStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}