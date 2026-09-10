package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import ru.ticketcraft.dto.ReleaseTicketCommand;
import ru.ticketcraft.dto.TicketReleasedEvent;
import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.EventRepository;
import ru.ticketcraft.repository.TicketRepository;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = { "ticketcraft.outbox.publisher.enabled=false" })
class TicketReleaseCommandProcessingIntegrationTest {

    private static final Long ORDER_ID = 3001L;

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
    void shouldReleaseTicketAndCreateReleasedOutboxEvent() throws Exception {

        // Given
        UUID reservationId = UUID.randomUUID();

        Ticket ticket = createReservedTicket("RELEASE-1", reservationId);

        ReleaseTicketCommand command = createCommand(ticket.getId(), reservationId);

        String expectedResultMessageId = resultMessageId(command);

        // When
        reservationService.processReleaseTicketCommand(command);

        // Then
        Ticket persistedTicket = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(persistedTicket.getStatus()).isEqualTo(TicketStatus.AVAILABLE);

        assertThat(persistedTicket.getReservationId()).isNull();

        assertThat(persistedTicket.getReservedUntil()).isNull();

        Integer outboxCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE message_id = ?
                """, Integer.class, expectedResultMessageId);

        assertThat(outboxCount).isEqualTo(1);

        Map<String, Object> outbox = jdbcTemplate.queryForMap("""
                SELECT
                    message_id,
                    aggregate_type,
                    aggregate_id,
                    event_type,
                    topic,
                    payload,
                    status
                FROM outbox_events
                WHERE message_id = ?
                """, expectedResultMessageId);

        assertThat(outbox.get("message_id")).isEqualTo(expectedResultMessageId);

        assertThat(outbox.get("aggregate_type")).isEqualTo("ORDER");

        assertThat(outbox.get("aggregate_id")).isEqualTo(ORDER_ID.toString());

        assertThat(outbox.get("event_type")).isEqualTo("TicketReleased");

        assertThat(outbox.get("topic")).isEqualTo("ticket-reservation-results");

        assertThat(outbox.get("status")).isEqualTo("PENDING");

        String payload = (String) outbox.get("payload");

        TicketReleasedEvent event = objectMapper.readValue(payload, TicketReleasedEvent.class);

        assertThat(event.messageId()).isEqualTo(expectedResultMessageId);

        assertThat(event.orderId()).isEqualTo(ORDER_ID);

        assertThat(event.ticketId()).isEqualTo(ticket.getId());

        assertThat(event.reservationId()).isEqualTo(reservationId);

        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void shouldTreatAlreadyAvailableTicketAsSuccessfulRelease() throws Exception {

        // Given
        UUID reservationId = UUID.randomUUID();

        Ticket ticket = createAvailableTicket("RELEASE-2");

        ReleaseTicketCommand command = createCommand(ticket.getId(), reservationId);

        String expectedResultMessageId = resultMessageId(command);

        // When
        reservationService.processReleaseTicketCommand(command);

        // Then
        Ticket persistedTicket = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(persistedTicket.getStatus()).isEqualTo(TicketStatus.AVAILABLE);

        assertThat(persistedTicket.getReservationId()).isNull();

        assertThat(persistedTicket.getReservedUntil()).isNull();

        Integer outboxCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE message_id = ?
                """, Integer.class, expectedResultMessageId);

        assertThat(outboxCount).isEqualTo(1);

        String payload = jdbcTemplate.queryForObject("""
                SELECT payload
                FROM outbox_events
                WHERE message_id = ?
                """, String.class, expectedResultMessageId);

        TicketReleasedEvent event = objectMapper.readValue(payload, TicketReleasedEvent.class);

        assertThat(event.orderId()).isEqualTo(ORDER_ID);

        assertThat(event.ticketId()).isEqualTo(ticket.getId());

        assertThat(event.reservationId()).isEqualTo(reservationId);
    }

    @Test
    void shouldIgnoreDuplicateReleaseCommand() {

        // Given
        UUID reservationId = UUID.randomUUID();

        Ticket ticket = createReservedTicket("RELEASE-3", reservationId);

        ReleaseTicketCommand command = createCommand(ticket.getId(), reservationId);

        String expectedResultMessageId = resultMessageId(command);

        // When
        reservationService.processReleaseTicketCommand(command);

        reservationService.processReleaseTicketCommand(command);

        // Then
        Ticket persistedTicket = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(persistedTicket.getStatus()).isEqualTo(TicketStatus.AVAILABLE);

        assertThat(persistedTicket.getReservationId()).isNull();

        assertThat(persistedTicket.getReservedUntil()).isNull();

        Integer outboxCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE message_id = ?
                """, Integer.class, expectedResultMessageId);

        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldNotReleaseTicketOwnedByAnotherReservation() {

        // Given
        UUID actualReservationId = UUID.randomUUID();

        UUID anotherReservationId = UUID.randomUUID();

        Ticket ticket = createReservedTicket("RELEASE-4", actualReservationId);

        ReleaseTicketCommand command = createCommand(ticket.getId(), anotherReservationId);

        String expectedResultMessageId = resultMessageId(command);

        // When / Then
        assertThatThrownBy(() -> reservationService.processReleaseTicketCommand(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot release ticket reserved by another reservation");

        Ticket persistedTicket = ticketRepository.findById(ticket.getId()).orElseThrow();

        /*
         * Главный invariant: чужая Saga не должна снять reservation.
         */
        assertThat(persistedTicket.getStatus()).isEqualTo(TicketStatus.RESERVED);

        assertThat(persistedTicket.getReservationId()).isEqualTo(actualReservationId);

        assertThat(persistedTicket.getReservedUntil()).isNotNull();

        Integer outboxCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE message_id = ?
                """, Integer.class, expectedResultMessageId);

        assertThat(outboxCount).isZero();
    }

    @Test
    void shouldFailWhenTicketDoesNotExist() {

        // Given
        UUID ticketId = UUID.randomUUID();

        UUID reservationId = UUID.randomUUID();

        ReleaseTicketCommand command = createCommand(ticketId, reservationId);

        String expectedResultMessageId = resultMessageId(command);

        // When / Then
        assertThatThrownBy(() -> reservationService.processReleaseTicketCommand(command))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ticket not found");

        Integer ticketCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM tickets
                WHERE id = ?
                """, Integer.class, ticketId);

        assertThat(ticketCount).isZero();

        Integer outboxCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE message_id = ?
                """, Integer.class, expectedResultMessageId);

        assertThat(outboxCount).isZero();
    }

    private ReleaseTicketCommand createCommand(UUID ticketId, UUID reservationId) {

        return new ReleaseTicketCommand("saga:" + reservationId + ":release-ticket", ORDER_ID, reservationId, ticketId,
                Instant.now());
    }

    private String resultMessageId(ReleaseTicketCommand command) {

        return "result:" + command.messageId();
    }

    private Ticket createReservedTicket(String seatNumber, UUID reservationId) {

        Event savedEvent = createEvent();

        Ticket ticket = new Ticket();

        ticket.setEvent(savedEvent);

        ticket.setSeatNumber(seatNumber);

        ticket.setPrice(new BigDecimal("100.00"));

        ticket.setStatus(TicketStatus.RESERVED);

        ticket.setReservationId(reservationId);

        ticket.setReservedUntil(Instant.now().plusSeconds(600));

        return ticketRepository.save(ticket);
    }

    private Ticket createAvailableTicket(String seatNumber) {

        Event savedEvent = createEvent();

        Ticket ticket = new Ticket();

        ticket.setEvent(savedEvent);

        ticket.setSeatNumber(seatNumber);

        ticket.setPrice(new BigDecimal("100.00"));

        ticket.setStatus(TicketStatus.AVAILABLE);

        return ticketRepository.save(ticket);
    }

    private Event createEvent() {

        Event event = new Event();

        event.setTitle("Ticket release integration test");

        event.setDescription("Saga compensation integration test");

        event.setEventDate(Instant.now().plusSeconds(3600));

        event.setVenue("Test venue");

        return eventRepository.save(event);
    }
}