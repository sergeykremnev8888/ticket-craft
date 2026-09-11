package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.EventRepository;
import ru.ticketcraft.repository.TicketRepository;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = { "ticketcraft.outbox.publisher.enabled=false",
        "ticketcraft.rate-limit.enabled=false" })
class TicketReservationCommandProcessingIntegrationTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long USER_ID = 501L;

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("catalog_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private TicketReservationService reservationService;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {

        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM tickets");
        jdbcTemplate.update("DELETE FROM events");
    }

    @Test
    void shouldReserveTicketAndCreateSuccessOutboxEvent() {

        // Given
        Ticket ticket = createAvailableTicket("A-1");

        UUID reservationId = UUID.randomUUID();

        ReserveTicketCommand command = createCommand(ticket.getId(), reservationId);

        String expectedResultMessageId = resultMessageId(command);

        // When
        reservationService.processReserveTicketCommand(command);

        // Then
        Ticket persistedTicket = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(persistedTicket.getStatus()).isEqualTo(TicketStatus.RESERVED);

        assertThat(persistedTicket.getReservationId()).isEqualTo(reservationId);

        assertThat(persistedTicket.getReservedUntil()).isNotNull();

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                """, Integer.class);

        assertThat(count).isEqualTo(1);

        Map<String, Object> outbox = jdbcTemplate.queryForMap("""
                SELECT
                    message_id,
                    aggregate_type,
                    aggregate_id,
                    event_type,
                    topic,
                    status
                FROM outbox_events
                """);

        assertThat(outbox.get("message_id")).isEqualTo(expectedResultMessageId);

        assertThat(outbox.get("aggregate_type")).isEqualTo("ORDER");

        assertThat(outbox.get("aggregate_id")).isEqualTo(ORDER_ID.toString());

        assertThat(outbox.get("event_type")).isEqualTo("TicketReserved");

        assertThat(outbox.get("topic")).isEqualTo("ticket-reservation-results");

        assertThat(outbox.get("status")).isEqualTo("PENDING");
    }

    @Test
    void shouldNotCreateDuplicateSuccessOutboxEventForSameReservation() {

        // Given
        Ticket ticket = createAvailableTicket("A-2");

        UUID reservationId = UUID.randomUUID();

        ReserveTicketCommand command = createCommand(ticket.getId(), reservationId);

        String expectedResultMessageId = resultMessageId(command);

        // When
        reservationService.processReserveTicketCommand(command);

        reservationService.processReserveTicketCommand(command);

        // Then
        Ticket persistedTicket = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(persistedTicket.getStatus()).isEqualTo(TicketStatus.RESERVED);

        assertThat(persistedTicket.getReservationId()).isEqualTo(reservationId);

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE message_id = ?
                """, Integer.class, expectedResultMessageId);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void shouldCreateFailureOutboxEventWhenTicketBelongsToAnotherReservation() throws Exception {

        // Given
        Ticket ticket = createAvailableTicket("A-3");

        UUID firstReservationId = UUID.randomUUID();

        ReserveTicketCommand firstCommand = createCommand(ticket.getId(), firstReservationId);

        reservationService.processReserveTicketCommand(firstCommand);

        UUID conflictingReservationId = UUID.randomUUID();

        ReserveTicketCommand conflictingCommand = createCommand(ticket.getId(), conflictingReservationId);

        String expectedResultMessageId = resultMessageId(conflictingCommand);

        // When
        reservationService.processReserveTicketCommand(conflictingCommand);

        // Then
        Ticket persistedTicket = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(persistedTicket.getStatus()).isEqualTo(TicketStatus.RESERVED);

        assertThat(persistedTicket.getReservationId()).isEqualTo(firstReservationId);

        String payload = jdbcTemplate.queryForObject("""
                SELECT payload
                FROM outbox_events
                WHERE message_id = ?
                """, String.class, expectedResultMessageId);

        assertThat(payload).isNotNull();

        TicketReservationFailedEvent event = objectMapper.readValue(payload, TicketReservationFailedEvent.class);

        assertThat(event.messageId()).isEqualTo(expectedResultMessageId);

        assertThat(event.orderId()).isEqualTo(ORDER_ID);

        assertThat(event.ticketId()).isEqualTo(ticket.getId());

        assertThat(event.reservationId()).isEqualTo(conflictingReservationId);

        assertThat(event.reason()).isEqualTo("TICKET_ALREADY_RESERVED");

        Integer failureEvents = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE event_type = 'TicketReservationFailed'
                """, Integer.class);

        assertThat(failureEvents).isEqualTo(1);
    }

    @Test
    void shouldCreateFailureOutboxEventWhenTicketDoesNotExist() throws Exception {

        // Given
        UUID ticketId = UUID.randomUUID();

        UUID reservationId = UUID.randomUUID();

        ReserveTicketCommand command = createCommand(ticketId, reservationId);

        String expectedResultMessageId = resultMessageId(command);

        // When
        reservationService.processReserveTicketCommand(command);

        // Then
        Integer ticketCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM tickets
                WHERE id = ?
                """, Integer.class, ticketId);

        assertThat(ticketCount).isZero();

        String payload = jdbcTemplate.queryForObject("""
                SELECT payload
                FROM outbox_events
                WHERE message_id = ?
                """, String.class, expectedResultMessageId);

        assertThat(payload).isNotNull();

        TicketReservationFailedEvent event = objectMapper.readValue(payload, TicketReservationFailedEvent.class);

        assertThat(event.messageId()).isEqualTo(expectedResultMessageId);

        assertThat(event.orderId()).isEqualTo(ORDER_ID);

        assertThat(event.ticketId()).isEqualTo(ticketId);

        assertThat(event.reservationId()).isEqualTo(reservationId);

        assertThat(event.reason()).isEqualTo("TICKET_NOT_FOUND");
    }

    @Test
    void shouldIgnoreRedeliveredCommandAfterReservationWasReleased() {

        // Given
        Ticket ticket = createAvailableTicket("A-4");

        UUID reservationId = UUID.randomUUID();

        ReserveTicketCommand command = createCommand(ticket.getId(), reservationId);

        String expectedResultMessageId = resultMessageId(command);

        /*
         * Первая обработка команды.
         */
        reservationService.processReserveTicketCommand(command);

        Ticket reservedTicket = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(reservedTicket.getStatus()).isEqualTo(TicketStatus.RESERVED);

        assertThat(reservedTicket.getReservationId()).isEqualTo(reservationId);

        /*
         * Имитируем истечение TTL / release reservation.
         */
        jdbcTemplate.update("""
                UPDATE tickets
                SET status = 'AVAILABLE',
                    reservation_id = NULL,
                    reserved_until = NULL
                WHERE id = ?
                """, ticket.getId());

        Ticket releasedTicket = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(releasedTicket.getStatus()).isEqualTo(TicketStatus.AVAILABLE);

        assertThat(releasedTicket.getReservationId()).isNull();

        /*
         * Kafka redelivery ТОЙ ЖЕ команды.
         *
         * Она уже была обработана, поэтому TicketReservationService должен сделать
         * no-op.
         */
        reservationService.processReserveTicketCommand(command);

        // Then
        Ticket afterRedelivery = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(afterRedelivery.getStatus()).isEqualTo(TicketStatus.AVAILABLE);

        assertThat(afterRedelivery.getReservationId()).isNull();

        assertThat(afterRedelivery.getReservedUntil()).isNull();

        Integer outboxCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE message_id = ?
                """, Integer.class, expectedResultMessageId);

        assertThat(outboxCount).isEqualTo(1);
    }

    private ReserveTicketCommand createCommand(UUID ticketId, UUID reservationId) {

        return new ReserveTicketCommand("saga:" + reservationId + ":reserve-ticket", ORDER_ID, reservationId, ticketId,
                USER_ID, Instant.now());
    }

    private String resultMessageId(ReserveTicketCommand command) {

        return "result:" + command.messageId();
    }

    private Ticket createAvailableTicket(String seatNumber) {

        Event event = new Event();

        event.setTitle("Saga integration test");
        event.setDescription("Ticket reservation command integration test");
        event.setEventDate(Instant.now().plusSeconds(3600));
        event.setVenue("Test venue");

        Event savedEvent = eventRepository.save(event);

        Ticket ticket = new Ticket();

        ticket.setEvent(savedEvent);
        ticket.setSeatNumber(seatNumber);
        ticket.setPrice(new BigDecimal("100.00"));
        ticket.setStatus(TicketStatus.AVAILABLE);

        return ticketRepository.save(ticket);
    }
}