package ru.ticketcraft.payment.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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

    public List<PaymentOutboxRecord> findUnpublished(int batchSize) {
        return jdbcTemplate.query("""
                SELECT
                    id,
                    message_id,
                    order_id,
                    event_type,
                    payload,
                    created_at
                FROM payment_outbox
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT ?
                """, ps -> ps.setInt(1, batchSize),
                (rs, rowNum) -> new PaymentOutboxRecord(rs.getObject("id", UUID.class), rs.getString("message_id"),
                        rs.getLong("order_id"), rs.getString("event_type"), rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    public int markPublished(UUID id, Instant publishedAt) {
        return jdbcTemplate.update("""
                UPDATE payment_outbox
                SET published_at = ?
                WHERE id = ?
                  AND published_at IS NULL
                """, Timestamp.from(publishedAt), id);
    }
}