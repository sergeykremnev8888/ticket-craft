package ru.ticketcraft.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = { 
        "ticketcraft.outbox.publisher.enabled=false",
        "spring.cache.type=none",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.enabled=false" })
class CatalogDatabaseIndexesIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("catalog_db")
            .withUsername("postgres")
            .withPassword("postgres");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCreateCatalogOptimizationIndexes() {

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                """, String.class);

        assertThat(indexes).contains("idx_events_event_date_id", "idx_tickets_reserved_until");
    }

    @Test
    void shouldRemoveRedundantTicketEventIdIndex() {

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'idx_tickets_event_id'
                """, Integer.class);

        assertThat(count).isZero();
    }

    @Test
    void shouldPreserveExistingTicketIndexes() {

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'tickets'
                """, String.class);

        assertThat(indexes).contains("idx_tickets_event_status", "idx_tickets_available");
    }
}