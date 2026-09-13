package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.exception.TicketAlreadyReservedException;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.TicketRepository;

@SpringBootTest(properties = {
        "ticketcraft.rate-limit.enabled=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.enabled=false"})
@Testcontainers
@ActiveProfiles("test")
class TicketReservationOwnershipIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("catalog_db").withUsername("postgres").withPassword("postgres");

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID RESERVATION_A = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID RESERVATION_B = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired
    private TicketReservationService reservationService;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {

        jdbcTemplate.update("DELETE FROM tickets");
        jdbcTemplate.update("DELETE FROM events");

        jdbcTemplate.update("""
                INSERT INTO events (
                    id,
                    title,
                    description,
                    event_date,
                    venue
                )
                VALUES (?, ?, ?, ?, ?)
                """, EVENT_ID, "Test Event", "Reservation ownership integration test",
                Timestamp.from(Instant.parse("2026-12-01T18:00:00Z")), "Test Venue");

        jdbcTemplate.update("""
                INSERT INTO tickets (
                    id,
                    event_id,
                    seat_number,
                    price,
                    status,
                    reservation_id,
                    reserved_until,
                    version,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, TICKET_ID, EVENT_ID, "A-1", new BigDecimal("100.00"), TicketStatus.AVAILABLE.name(), null, null,
                0L);
    }

    @Test
    void shouldPreserveReservationOwnershipAcrossDuplicateAndConflictingRequests() {

        /*
         * 1. Saga A резервирует AVAILABLE ticket.
         */
        reservationService.reserveTicket(TICKET_ID, RESERVATION_A);

        Ticket afterFirstReservation = ticketRepository.findById(TICKET_ID).orElseThrow();

        assertThat(afterFirstReservation.getStatus()).isEqualTo(TicketStatus.RESERVED);

        assertThat(afterFirstReservation.getReservationId()).isEqualTo(RESERVATION_A);

        assertThat(afterFirstReservation.getReservedUntil()).isNotNull();

        /*
         * 2. Kafka redelivery той же команды.
         *
         * Ticket уже RESERVED тем же reservationId. Это должен быть idempotent success.
         */
        assertThatCode(() -> reservationService.reserveTicket(TICKET_ID, RESERVATION_A)).doesNotThrowAnyException();

        Ticket afterDuplicate = ticketRepository.findById(TICKET_ID).orElseThrow();

        assertThat(afterDuplicate.getStatus()).isEqualTo(TicketStatus.RESERVED);

        assertThat(afterDuplicate.getReservationId()).isEqualTo(RESERVATION_A);

        /*
         * 3. Другая Saga пытается забрать тот же ticket.
         *
         * Это уже настоящий ownership conflict.
         */
        assertThatThrownBy(() -> reservationService.reserveTicket(TICKET_ID, RESERVATION_B))
                .isInstanceOf(TicketAlreadyReservedException.class);

        /*
         * 4. Самое важное: failed reservation B не должна изменить ownership.
         */
        Ticket afterConflictingReservation = ticketRepository.findById(TICKET_ID).orElseThrow();

        assertThat(afterConflictingReservation.getStatus()).isEqualTo(TicketStatus.RESERVED);

        assertThat(afterConflictingReservation.getReservationId()).isEqualTo(RESERVATION_A);
    }
}