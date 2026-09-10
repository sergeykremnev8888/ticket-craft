package ru.ticketcraft.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventRepository(JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertIfAbsent(String messageId) {

        int inserted = jdbcTemplate.update("""
                INSERT INTO processed_events (
                    message_id
                )
                VALUES (?)
                ON CONFLICT (message_id) DO NOTHING
                """, messageId);

        return inserted == 1;
    }
}