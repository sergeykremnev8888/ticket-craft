package ru.ticketcraft.payment.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PaymentOutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public PaymentOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertIfAbsent(UUID id, String messageId, Long orderId, String eventType, String payload,
            Instant createdAt) {

        int affectedRows = jdbcTemplate.update("""
                INSERT INTO payment_outbox (
                    id,
                    message_id,
                    order_id,
                    event_type,
                    payload,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (message_id) DO NOTHING
                """, id, messageId, orderId, eventType, payload, Timestamp.from(createdAt));

        return affectedRows == 1;
    }

    @Transactional
    public List<PaymentOutboxRecord> claimBatch(int batchSize, String claimedBy, Instant claimedAt,
            Instant claimExpiredBefore) {
        return jdbcTemplate.query("""
                WITH candidates AS (
                    SELECT id
                    FROM payment_outbox
                    WHERE published_at IS NULL
                      AND (
                          claimed_at IS NULL
                          OR claimed_at < ?
                      )
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE payment_outbox o
                SET claimed_at = ?,
                    claimed_by = ?
                FROM candidates c
                WHERE o.id = c.id
                RETURNING
                    o.id,
                    o.message_id,
                    o.order_id,
                    o.event_type,
                    o.payload,
                    o.created_at,
                    o.claimed_at,
                    o.claimed_by
                """, ps -> {
            ps.setTimestamp(1, Timestamp.from(claimExpiredBefore));
            ps.setInt(2, batchSize);
            ps.setTimestamp(3, Timestamp.from(claimedAt));
            ps.setString(4, claimedBy);
        }, (rs, rowNum) -> new PaymentOutboxRecord(rs.getObject("id", UUID.class), rs.getString("message_id"),
                rs.getLong("order_id"), rs.getString("event_type"), rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("claimed_at").toInstant(),
                rs.getString("claimed_by")));
    }

    public int markPublished(UUID id, String claimedBy, Instant publishedAt) {
        return jdbcTemplate.update("""
                UPDATE payment_outbox
                SET published_at = ?,
                    claimed_at = NULL,
                    claimed_by = NULL
                WHERE id = ?
                  AND published_at IS NULL
                  AND claimed_by = ?
                """, Timestamp.from(publishedAt), id, claimedBy);
    }

    public int releaseClaim(UUID id, String claimedBy) {
        return jdbcTemplate.update("""
                UPDATE payment_outbox
                SET claimed_at = NULL,
                    claimed_by = NULL
                WHERE id = ?
                  AND published_at IS NULL
                  AND claimed_by = ?
                """, id, claimedBy);
    }
}