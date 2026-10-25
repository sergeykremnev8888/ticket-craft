package ru.ticketcraft.payment.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJdbcTest
@Import(PaymentOutboxRepository.class)
@Testcontainers
@ActiveProfiles("test")
class PaymentOutboxRepositoryIntegrationTest {

    private static final UUID OUTBOX_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String MESSAGE_ID = "payment:test:succeeded";

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");

    private static final String PAYLOAD = """
            {
              "messageId": "payment:test:succeeded",
              "orderId": 123,
              "paymentId": "22222222-2222-2222-2222-222222222222",
              "amount": 1500.00,
              "createdAt": "2026-01-01T10:00:00Z"
            }
            """;

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres:16-alpine").withDatabaseName("payment_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private PaymentOutboxRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistOutboxEventForDebezium() {
        boolean inserted = repository.insertIfAbsent(OUTBOX_ID,
                                                     MESSAGE_ID,
                                                     123L,
                                                     PaymentOutboxEventType.PAYMENT_SUCCEEDED.getPersistedValue(),
                                                     PAYLOAD,
                                                     CREATED_AT);

        assertThat(inserted).isTrue();

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT
                    id,
                    message_id,
                    order_id,
                    event_type,
                    payload ->> 'messageId' AS payload_message_id
                FROM payment_outbox
                WHERE id = ?
                """, OUTBOX_ID);

        assertThat(row.get("id")).isEqualTo(OUTBOX_ID);
        assertThat(row.get("message_id")).isEqualTo(MESSAGE_ID);
        assertThat(row.get("order_id")).isEqualTo(123L);
        assertThat(row.get("event_type")).isEqualTo("PaymentSucceededEvent");
        assertThat(row.get("payload_message_id")).isEqualTo(MESSAGE_ID);
    }

    @Test
    void shouldKeepOriginalEventWhenMessageIdIsRepeated() {
        boolean firstInserted = repository.insertIfAbsent(OUTBOX_ID,
                                                          MESSAGE_ID,
                                                          123L,
                                                          PaymentOutboxEventType.PAYMENT_SUCCEEDED.getPersistedValue(),
                                                          PAYLOAD,
                                                          CREATED_AT);

        boolean duplicateInserted = repository.insertIfAbsent(UUID.randomUUID(),
                                                              MESSAGE_ID,
                                                              999L,
                                                              PaymentOutboxEventType.PAYMENT_FAILED.getPersistedValue(),
                                                              "{}",
                                                              CREATED_AT.plusSeconds(1));

        assertThat(firstInserted).isTrue();
        assertThat(duplicateInserted).isFalse();

        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM payment_outbox
                WHERE message_id = ?
                """, Long.class, MESSAGE_ID);

        assertThat(count).isEqualTo(1L);

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT id, order_id, event_type
                FROM payment_outbox
                WHERE message_id = ?
                """, MESSAGE_ID);

        assertThat(row.get("id")).isEqualTo(OUTBOX_ID);
        assertThat(row.get("order_id")).isEqualTo(123L);
        assertThat(row.get("event_type")).isEqualTo("PaymentSucceededEvent");
    }
}