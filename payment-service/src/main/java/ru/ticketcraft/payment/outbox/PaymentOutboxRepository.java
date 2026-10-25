package ru.ticketcraft.payment.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentOutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public PaymentOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertIfAbsent(UUID id,
                                  String messageId,
                                  Long orderId,
                                  String eventType,
                                  String payload,
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
}