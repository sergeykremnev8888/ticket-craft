package ru.ticketcraft.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import ru.ticketcraft.model.OutboxEvent;

public interface OutboxEventRepository extends CrudRepository<OutboxEvent, UUID> {

    @Modifying
    @Query("""
            INSERT INTO outbox_events (
                id,
                aggregate_type,
                aggregate_id,
                event_type,
                topic,
                payload,
                status,
                created_at
            )
            VALUES (
                :id,
                :aggregateType,
                :aggregateId,
                :eventType,
                :topic,
                :payload,
                'PENDING',
                :createdAt
            )
            """)
    int insert(@Param("id") UUID id, @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId, @Param("eventType") String eventType,
            @Param("topic") String topic, @Param("payload") String payload, @Param("createdAt") Instant createdAt);

    @Modifying
    @Query("""
            UPDATE outbox_events
            SET claim_id = :claimId,
                locked_at = :lockedAt,
                locked_by = :lockedBy,
                attempts = attempts + 1
            WHERE id IN (
                SELECT id
                FROM outbox_events
                WHERE status = 'PENDING'
                  AND next_attempt_at <= :now
                  AND (
                      locked_at IS NULL
                      OR locked_at < :lockExpiration
                  )
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            """)
    int claimPending(@Param("claimId") UUID claimId, @Param("now") Instant now,
            @Param("lockExpiration") Instant lockExpiration, @Param("lockedAt") Instant lockedAt,
            @Param("lockedBy") String lockedBy, @Param("limit") int limit);

    @Query("""
            SELECT *
            FROM outbox_events
            WHERE status = 'PENDING'
              AND claim_id = :claimId
            ORDER BY created_at
            """)
    List<OutboxEvent> findClaimed(@Param("claimId") UUID claimId);

    @Modifying
    @Query("""
            UPDATE outbox_events
            SET status = 'PUBLISHED',
                published_at = CURRENT_TIMESTAMP,
                locked_at = NULL,
                locked_by = NULL,
                claim_id = NULL
            WHERE id = :id
              AND status = 'PENDING'
              AND claim_id = :claimId
            """)
    int markPublished(@Param("id") UUID id, @Param("claimId") UUID claimId);

    @Modifying
    @Query("""
            UPDATE outbox_events
            SET locked_at = NULL,
                locked_by = NULL,
                claim_id = NULL,
                next_attempt_at = :nextAttemptAt
            WHERE id = :id
              AND status = 'PENDING'
              AND claim_id = :claimId
            """)
    int releaseClaim(@Param("id") UUID id, @Param("claimId") UUID claimId,
            @Param("nextAttemptAt") Instant nextAttemptAt);
}
