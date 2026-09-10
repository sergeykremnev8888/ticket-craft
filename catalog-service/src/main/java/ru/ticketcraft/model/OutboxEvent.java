package ru.ticketcraft.model;

import java.time.Instant;
import java.util.UUID;

public class OutboxEvent {

    private final UUID id;
    private final String messageId;
    private final String aggregateType;
    private final String aggregateId;
    private final String eventType;
    private final String topic;
    private final String payload;
    private final OutboxStatus status;
    private final Instant createdAt;
    private final Instant publishedAt;
    private final Integer attempts;
    private final Instant nextAttemptAt;
    private final Instant lockedAt;
    private final String lockedBy;
    private final UUID claimId;

    public OutboxEvent(UUID id, String messageId, String aggregateType, String aggregateId, String eventType,
            String topic, String payload, OutboxStatus status, Instant createdAt, Instant publishedAt, Integer attempts,
            Instant nextAttemptAt, Instant lockedAt, String lockedBy, UUID claimId) {

        this.id = id;
        this.messageId = messageId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.status = status;
        this.createdAt = createdAt;
        this.publishedAt = publishedAt;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.lockedAt = lockedAt;
        this.lockedBy = lockedBy;
        this.claimId = claimId;
    }

    public UUID getId() {
        return id;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public UUID getClaimId() {
        return claimId;
    }
}