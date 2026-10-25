package ru.ticketcraft.catalog.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public OutboxEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertIfAbsent(UUID id,
                                  String messageId,
                                  String aggregateType,
                                  String aggregateId,
                                  String eventType,
                                  String topic,
                                  String payload,
                                  Instant createdAt) {

        int affectedRows = jdbcTemplate.update("""
                INSERT INTO outbox_events (
                    id,
                    message_id,
                    aggregate_type,
                    aggregate_id,
                    event_type,
                    topic,
                    payload,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (message_id) DO NOTHING
                """, id, messageId, aggregateType, aggregateId, eventType, topic, payload, Timestamp.from(createdAt));

        return affectedRows == 1;
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