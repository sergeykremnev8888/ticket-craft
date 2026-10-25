package ru.ticketcraft.order.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.order.model.OutboxEvent;
import ru.ticketcraft.order.repository.OutboxEventRepository;

@Testcontainers
@DataJdbcTest
class OutboxEventRepositoryTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");

    private static final String PAYLOAD = "{\"messageId\":\"saga:1:reserve-ticket\",\"orderId\":123}";

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres:16-alpine").withDatabaseName("order_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private OutboxEventRepository repository;

    @Test
    void shouldPersistOutboxEventForDebezium() {
        int inserted = repository.insert(EVENT_ID,
                                         "ORDER",
                                         "123",
                                         "ReserveTicket",
                                         "ticket-reservation-commands",
                                         PAYLOAD,
                                         CREATED_AT);

        assertEquals(1, inserted);

        OutboxEvent event = repository.findById(EVENT_ID).orElseThrow();

        assertEquals(EVENT_ID, event.getId());
        assertEquals("ORDER", event.getAggregateType());
        assertEquals("123", event.getAggregateId());
        assertEquals("ReserveTicket", event.getEventType());
        assertEquals("ticket-reservation-commands", event.getTopic());
        assertEquals(PAYLOAD, event.getPayload());
        assertEquals(CREATED_AT, event.getCreatedAt());
    }
}