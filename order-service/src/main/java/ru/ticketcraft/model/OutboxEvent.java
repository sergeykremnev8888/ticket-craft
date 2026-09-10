package ru.ticketcraft.model;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column("aggregate_type")
    private String aggregateType;

    @Column("aggregate_id")
    private String aggregateId;

    @Column("event_type")
    private String eventType;

    @Column("payload")
    private String payload;

    @Column("status")
    private OutboxStatus status;

    @Column("created_at")
    private Instant createdAt;

    @Column("published_at")
    private Instant publishedAt;

    @Column("attempts")
    private Integer attempts;

    @Column("next_attempt_at")
    private Instant nextAttemptAt;

    @Column("locked_at")
    private Instant lockedAt;

    @Column("locked_by")
    private String lockedBy;

    @Column("claim_id")
    private UUID claimId;

    @Column("topic")
    private String topic;

    public OutboxEvent() {
    }

    public OutboxEvent(UUID id, String aggregateType, String aggregateId, String eventType, String topic,
            String payload, OutboxStatus status, Instant createdAt, Instant publishedAt, Integer attempts,
            Instant nextAttemptAt, Instant lockedAt, String lockedBy, UUID claimId) {

        this.id = id;
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

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
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

    public String getTopic() {
        return topic;
    }

    public void markPublished(Instant publishedAt) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.lockedAt = null;
        this.lockedBy = null;
        this.claimId = null;
    }
}