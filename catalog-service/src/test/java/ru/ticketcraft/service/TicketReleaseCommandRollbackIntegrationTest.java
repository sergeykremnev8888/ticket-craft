package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

@Testcontainers
@SpringBootTest(properties = { "ticketcraft.outbox.publisher.enabled=false" })
class TicketReleaseCommandRollbackIntegrationTest {

    private static final Long ORDER_ID = 4001L;

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

    @MockitoBean
    private OutboxService outboxService;

    @AfterEach
    void cleanUp() {

        jdbcTemplate.update("DELETE FROM outbox_events");

        jdbcTemplate.update("DELETE FROM tickets");

        jdbcTemplate.update("DELETE FROM events");
    }

    @Test
    void shouldRollbackTicketReleaseWhenOutboxWriteFails() {

        // Given
        UUID reservationId = UUID.randomUUID();

        Ticket ticket = createReservedTicket("RELEASE-ROLLBACK-1", reservationId);

        Instant originalReservedUntil = ticket.getReservedUntil();

        ReleaseTicketCommand command = new ReleaseTicketCommand("saga:" + reservationId + ":release-ticket", ORDER_ID,
                reservationId, ticket.getId(), Instant.now());

        doThrow(new IllegalStateException("Simulated outbox failure")).when(outboxService)
                .saveTicketReleasedEvent(any(TicketReleasedEvent.class));

        // When / Then
        assertThatThrownBy(() -> reservationService.processReleaseTicketCommand(command))
                .isInstanceOf(IllegalStateException.class).hasMessage("Simulated outbox failure");

        /*
         * Читаем заново ПОСЛЕ завершения failed transaction.
         */
        Ticket persistedTicket = ticketRepository.findById(ticket.getId()).orElseThrow();

        /*
         * Критический transactional-outbox invariant:
         *
         * release UPDATE был выполнен, но outbox write упал, поэтому весь release
         * обязан откатиться.
         */
        assertThat(persistedTicket.getStatus()).isEqualTo(TicketStatus.RESERVED);

        assertThat(persistedTicket.getReservationId()).isEqualTo(reservationId);

        assertThat(persistedTicket.getReservedUntil()).isCloseTo(originalReservedUntil, within(1, ChronoUnit.MICROS));

        Integer outboxCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                """, Integer.class);

        assertThat(outboxCount).isZero();
    }

    private Ticket createReservedTicket(String seatNumber, UUID reservationId) {

        Event event = new Event();
        event.setTitle("Ticket release rollback test");
        event.setDescription("Transactional compensation test");
        event.setEventDate(Instant.now().plusSeconds(3600));
        event.setVenue("Test venue");

        Event savedEvent = eventRepository.save(event);

        Ticket ticket = new Ticket();
        ticket.setEvent(savedEvent);
        ticket.setSeatNumber(seatNumber);
        ticket.setPrice(new BigDecimal("150.00"));
        ticket.setStatus(TicketStatus.RESERVED);
        ticket.setReservationId(reservationId);
        ticket.setReservedUntil(Instant.now().plusSeconds(600));

        return ticketRepository.save(ticket);
    }
}