package ru.ticketcraft.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Atomically registers an event as processed.
     *
     * @return 1 if the event was inserted for the first time, 0 if the event has
     *         already been processed
     */
    public int insertIfAbsent(String messageId) {
        return jdbcTemplate.update("""
                INSERT INTO processed_events (message_id)
                VALUES (?)
                ON CONFLICT (message_id) DO NOTHING
                """, messageId);
    }
}
