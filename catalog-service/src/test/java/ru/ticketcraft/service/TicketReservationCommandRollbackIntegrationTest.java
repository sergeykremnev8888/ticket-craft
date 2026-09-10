package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.Instant;
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

import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.dto.TicketReservedEvent;
import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.EventRepository;
import ru.ticketcraft.repository.TicketRepository;

@Testcontainers
@SpringBootTest(properties = { "ticketcraft.outbox.publisher.enabled=false" })
class TicketReservationCommandRollbackIntegrationTest {

    private static final Long ORDER_ID = 2001L;
    private static final Long USER_ID = 701L;

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
    void shouldRollbackTicketReservationWhenSuccessOutboxWriteFails() {

        // Given
        Ticket ticket = createAvailableTicket("ROLLBACK-1");

        UUID reservationId = UUID.randomUUID();

        ReserveTicketCommand command = new ReserveTicketCommand("saga:" + reservationId + ":reserve-ticket", ORDER_ID,
                reservationId, ticket.getId(), USER_ID, Instant.now());

        doThrow(new IllegalStateException("Simulated outbox failure")).when(outboxService)
                .saveTicketReservedEvent(any(TicketReservedEvent.class));

        // When / Then
        assertThatThrownBy(() -> reservationService.processReserveTicketCommand(command))
                .isInstanceOf(IllegalStateException.class).hasMessage("Simulated outbox failure");

        /*
         * ВАЖНО: читаем состояние заново после завершения failed transaction.
         */
        Ticket persistedTicket = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(persistedTicket.getStatus()).isEqualTo(TicketStatus.AVAILABLE);

        assertThat(persistedTicket.getReservationId()).isNull();

        assertThat(persistedTicket.getReservedUntil()).isNull();

        Integer outboxCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                """, Integer.class);

        assertThat(outboxCount).isZero();
    }

    private Ticket createAvailableTicket(String seatNumber) {

        Event event = new Event();

        event.setTitle("Transactional outbox rollback test");

        event.setDescription("Rollback integration test");

        event.setEventDate(Instant.now().plusSeconds(3600));

        event.setVenue("Test venue");

        Event savedEvent = eventRepository.save(event);

        Ticket ticket = new Ticket();

        ticket.setEvent(savedEvent);
        ticket.setSeatNumber(seatNumber);

        ticket.setPrice(new BigDecimal("150.00"));

        ticket.setStatus(TicketStatus.AVAILABLE);

        return ticketRepository.save(ticket);
    }
}