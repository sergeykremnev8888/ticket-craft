package ru.ticketcraft.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.model.OutboxEvent;
import ru.ticketcraft.model.OutboxStatus;

@Repository
public class OutboxEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public OutboxEventRepository(JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertIfAbsent(UUID id, String messageId, String aggregateType, String aggregateId, String eventType,
            String topic, String payload, Instant createdAt) {

        Timestamp timestamp = Timestamp.from(createdAt);

        int affectedRows = jdbcTemplate.update("""
                INSERT INTO outbox_events (
                    id,
                    message_id,
                    aggregate_type,
                    aggregate_id,
                    event_type,
                    topic,
                    payload,
                    status,
                    created_at,
                    next_attempt_at
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?
                )
                ON CONFLICT (message_id) DO NOTHING
                """, id, messageId, aggregateType, aggregateId, eventType, topic, payload, timestamp, timestamp);

        return affectedRows == 1;
    }

    @Transactional
    public int claimPending(UUID claimId, Instant now, Instant lockExpiration, Instant lockedAt, String lockedBy,
            int limit) {

        return jdbcTemplate.update("""
                UPDATE outbox_events
                SET claim_id = ?,
                    locked_at = ?,
                    locked_by = ?,
                    attempts = attempts + 1
                WHERE id IN (
                    SELECT id
                    FROM outbox_events
                    WHERE status = 'PENDING'
                      AND next_attempt_at <= ?
                      AND (
                          locked_at IS NULL
                          OR locked_at < ?
                      )
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                """, claimId, Timestamp.from(lockedAt), lockedBy, Timestamp.from(now), Timestamp.from(lockExpiration),
                limit);
    }

    public List<OutboxEvent> findClaimed(UUID claimId) {

        return jdbcTemplate.query("""
                SELECT
                    id,
                    message_id,
                    aggregate_type,
                    aggregate_id,
                    event_type,
                    topic,
                    payload,
                    status,
                    created_at,
                    published_at,
                    attempts,
                    next_attempt_at,
                    locked_at,
                    locked_by,
                    claim_id
                FROM outbox_events
                WHERE status = 'PENDING'
                  AND claim_id = ?
                ORDER BY created_at
                """, (rs, rowNum) -> mapOutboxEvent(rs), claimId);
    }

    public int markPublished(UUID id, UUID claimId, Instant publishedAt) {

        return jdbcTemplate.update("""
                UPDATE outbox_events
                SET status = 'PUBLISHED',
                    published_at = ?,
                    locked_at = NULL,
                    locked_by = NULL,
                    claim_id = NULL
                WHERE id = ?
                  AND status = 'PENDING'
                  AND claim_id = ?
                """, Timestamp.from(publishedAt), id, claimId);
    }

    public int releaseClaim(UUID id, UUID claimId, Instant nextAttemptAt) {

        return jdbcTemplate.update("""
                UPDATE outbox_events
                SET locked_at = NULL,
                    locked_by = NULL,
                    claim_id = NULL,
                    next_attempt_at = ?
                WHERE id = ?
                  AND status = 'PENDING'
                  AND claim_id = ?
                """, Timestamp.from(nextAttemptAt), id, claimId);
    }

    private OutboxEvent mapOutboxEvent(ResultSet rs) throws SQLException {

        Timestamp publishedAt = rs.getTimestamp("published_at");
        Timestamp nextAttemptAt = rs.getTimestamp("next_attempt_at");
        Timestamp lockedAt = rs.getTimestamp("locked_at");

        return new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("message_id"),
                rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getString("event_type"),
                rs.getString("topic"), rs.getString("payload"), OutboxStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(), publishedAt != null ? publishedAt.toInstant() : null,
                rs.getInt("attempts"), nextAttemptAt != null ? nextAttemptAt.toInstant() : null,
                lockedAt != null ? lockedAt.toInstant() : null, rs.getString("locked_by"),
                rs.getObject("claim_id", UUID.class));
    }

    public boolean existsByMessageId(String messageId) {

        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM outbox_events
                    WHERE message_id = ?
                )
                """, Boolean.class, messageId);

        return Boolean.TRUE.equals(exists);
    }
}