package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.config.OutboxPublisherProperties;
import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketReservedEvent;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = {
        /*
         * Отключаем настоящий scheduled publisher.
         *
         * В тесте создадим OutboxPublisher вручную, чтобы полностью контролировать
         * момент publish.
         */
        "ticketcraft.outbox.publisher.enabled=false",
        "ticketcraft.rate-limit.enabled=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.enabled=false"
})
class OutboxPublisherIntegrationTest {

    private static final String RESULT_TOPIC = "ticket-reservation-results";

    private static final Long ORDER_ID = 3001L;

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("catalog_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxClaimService outboxClaimService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {

        reset(kafkaTemplate);

        OutboxPublisherProperties properties = new OutboxPublisherProperties(true, 100, Duration.ofSeconds(1),
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(10));

        /*
         * Создаём publisher вручную с enabled=true.
         *
         * Spring-managed publisher при этом disabled, поэтому scheduler не мешает
         * тесту.
         */
        publisher = new OutboxPublisher(outboxClaimService, kafkaTemplate, objectMapper, properties);
    }

    @AfterEach
    void cleanUp() {

        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @Test
    void shouldClaimPublishAndMarkTicketReservedEventAsPublished() {

        // Given
        UUID reservationId = UUID.randomUUID();

        UUID ticketId = UUID.randomUUID();

        String messageId = "result:" + reservationId;

        TicketReservedEvent event = new TicketReservedEvent(messageId, ORDER_ID, reservationId, ticketId,
                Instant.now());

        boolean inserted = outboxService.saveTicketReservedEvent(event);

        assertThat(inserted).isTrue();

        Map<String, Object> beforePublish = loadByMessageId(messageId);

        assertThat(beforePublish.get("status")).isEqualTo("PENDING");

        assertThat(beforePublish.get("claim_id")).isNull();

        assertThat(((Number) beforePublish.get("attempts")).intValue()).isZero();

        CompletableFuture<SendResult<String, Object>> successfulFuture = CompletableFuture.completedFuture(null);

        when(kafkaTemplate.send(eq(RESULT_TOPIC), eq(ORDER_ID.toString()), any())).thenReturn(successfulFuture);

        // When
        publisher.publishPendingEvents();

        // Then
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        verify(kafkaTemplate).send(eq(RESULT_TOPIC), eq(ORDER_ID.toString()), payloadCaptor.capture());

        assertThat(payloadCaptor.getValue()).isInstanceOf(TicketReservedEvent.class);

        TicketReservedEvent publishedEvent = (TicketReservedEvent) payloadCaptor.getValue();

        assertThat(publishedEvent.messageId()).isEqualTo(messageId);

        assertThat(publishedEvent.orderId()).isEqualTo(ORDER_ID);

        assertThat(publishedEvent.reservationId()).isEqualTo(reservationId);

        assertThat(publishedEvent.ticketId()).isEqualTo(ticketId);

        Map<String, Object> afterPublish = loadByMessageId(messageId);

        assertThat(afterPublish.get("status")).isEqualTo("PUBLISHED");

        assertThat(afterPublish.get("published_at")).isNotNull();

        assertThat(afterPublish.get("claim_id")).isNull();

        assertThat(afterPublish.get("locked_at")).isNull();

        assertThat(afterPublish.get("locked_by")).isNull();

        assertThat(((Number) afterPublish.get("attempts")).intValue()).isEqualTo(1);
    }

    @Test
    void shouldClaimPublishAndMarkTicketReservationFailedEventAsPublished() {

        // Given
        UUID reservationId = UUID.randomUUID();

        UUID ticketId = UUID.randomUUID();

        String commandMessageId = "saga:" + reservationId + ":reserve-ticket";

        String messageId = "result:" + commandMessageId;

        TicketReservationFailedEvent event = new TicketReservationFailedEvent(messageId, ORDER_ID, reservationId,
                ticketId, "TICKET_ALREADY_RESERVED", Instant.now());

        boolean inserted = outboxService.saveTicketReservationFailedEvent(event);

        assertThat(inserted).isTrue();

        when(kafkaTemplate.send(eq(RESULT_TOPIC), eq(ORDER_ID.toString()), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // When
        publisher.publishPendingEvents();

        // Then
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        verify(kafkaTemplate).send(eq(RESULT_TOPIC), eq(ORDER_ID.toString()), payloadCaptor.capture());

        assertThat(payloadCaptor.getValue()).isInstanceOf(TicketReservationFailedEvent.class);

        TicketReservationFailedEvent publishedEvent = (TicketReservationFailedEvent) payloadCaptor.getValue();

        assertThat(publishedEvent.messageId()).isEqualTo(messageId);

        assertThat(publishedEvent.reason()).isEqualTo("TICKET_ALREADY_RESERVED");

        Map<String, Object> outbox = loadByMessageId(messageId);

        assertThat(outbox.get("status")).isEqualTo("PUBLISHED");

        assertThat(outbox.get("published_at")).isNotNull();

        assertThat(outbox.get("claim_id")).isNull();

        assertThat(((Number) outbox.get("attempts")).intValue()).isEqualTo(1);
    }

    @Test
    void shouldReleaseClaimAndScheduleRetryWhenKafkaSendFails() {

        // Given
        UUID reservationId = UUID.randomUUID();

        UUID ticketId = UUID.randomUUID();

        String messageId = "result:" + reservationId;

        TicketReservedEvent event = new TicketReservedEvent(messageId, ORDER_ID, reservationId, ticketId,
                Instant.now());

        boolean inserted = outboxService.saveTicketReservedEvent(event);

        assertThat(inserted).isTrue();

        when(kafkaTemplate.send(eq(RESULT_TOPIC), eq(ORDER_ID.toString()), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable")));

        Instant beforePublish = Instant.now();

        // When
        publisher.publishPendingEvents();

        Instant afterPublish = Instant.now();

        // Then
        verify(kafkaTemplate).send(eq(RESULT_TOPIC), eq(ORDER_ID.toString()), any());

        Map<String, Object> outbox = loadByMessageId(messageId);

        /*
         * Kafka ACK не получен: event обязан остаться PENDING.
         */
        assertThat(outbox.get("status")).isEqualTo("PENDING");

        assertThat(outbox.get("published_at")).isNull();

        /*
         * Claim обязан быть освобождён, чтобы другая instance могла подобрать event
         * после retry delay.
         */
        assertThat(outbox.get("claim_id")).isNull();

        assertThat(outbox.get("locked_at")).isNull();

        assertThat(outbox.get("locked_by")).isNull();

        /*
         * Попытка была реально сделана.
         */
        assertThat(((Number) outbox.get("attempts")).intValue()).isEqualTo(1);

        Instant nextAttemptAt = ((java.sql.Timestamp) outbox.get("next_attempt_at")).toInstant();

        /*
         * properties.retryDelay() = 5 seconds.
         */
        assertThat(nextAttemptAt).isAfterOrEqualTo(beforePublish.plusSeconds(5));

        assertThat(nextAttemptAt).isBeforeOrEqualTo(afterPublish.plusSeconds(5));
    }

    private Map<String, Object> loadByMessageId(String messageId) {

        return jdbcTemplate.queryForMap("""
                SELECT
                    id,
                    message_id,
                    aggregate_type,
                    aggregate_id,
                    event_type,
                    topic,
                    status,
                    published_at,
                    attempts,
                    next_attempt_at,
                    locked_at,
                    locked_by,
                    claim_id
                FROM outbox_events
                WHERE message_id = ?
                """, messageId);
    }
}