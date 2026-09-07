package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.EventRepository;
import ru.ticketcraft.repository.TicketRepository;

@Testcontainers
@SpringBootTest
class TicketReservationExpirationServiceTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("catalog_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private TicketReservationExpirationService expirationService;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EventRepository eventRepository;

    @BeforeEach
    void cleanDatabase() {
        ticketRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();

        assertThat(ticketRepository.count()).isZero();
        assertThat(eventRepository.count()).isZero();
    }

    @Test
    void shouldReleaseExpiredReservation() {
        // Given
        Ticket ticket = createTicket(TicketStatus.RESERVED, UUID.randomUUID(), Instant.now().minusSeconds(60));

        UUID ticketId = ticket.getId();

        // When
        int released = expirationService.releaseExpiredReservations();

        // Then
        assertThat(released).as("Expired reservation must be released").isEqualTo(1);

        Ticket releasedTicket = ticketRepository.findById(ticketId).orElseThrow();

        assertThat(releasedTicket.getStatus()).isEqualTo(TicketStatus.AVAILABLE);

        assertThat(releasedTicket.getReservationId()).isNull();

        assertThat(releasedTicket.getReservedUntil()).isNull();

        assertThat(releasedTicket.getVersion()).isEqualTo(1L);
    }

    @Test
    void shouldNotReleaseActiveReservation() {
        // Given
        Ticket ticket = createTicket(TicketStatus.RESERVED, UUID.randomUUID(), Instant.now().plusSeconds(600));

        UUID ticketId = ticket.getId();
        UUID reservationId = ticket.getReservationId();
        Instant reservedUntil = ticket.getReservedUntil();

        // When
        int released = expirationService.releaseExpiredReservations();

        // Then
        assertThat(released).as("Active reservation must not be released").isEqualTo(0);

        Ticket actualTicket = ticketRepository.findById(ticketId).orElseThrow();

        assertThat(actualTicket.getStatus()).isEqualTo(TicketStatus.RESERVED);

        assertThat(actualTicket.getReservationId()).isEqualTo(reservationId);

        assertThat(actualTicket.getReservedUntil()).isEqualTo(reservedUntil);

        assertThat(actualTicket.getVersion()).isEqualTo(0L);
    }

    @Test
    void shouldReleaseOnlyExpiredReservations() {
        // Given
        assertThat(ticketRepository.count()).as("Database must be empty before test").isZero();

        Instant now = Instant.now();

        Ticket expiredTicket = createTicket(TicketStatus.RESERVED, UUID.randomUUID(), now.minusSeconds(3600));

        Ticket activeTicket = createTicket(TicketStatus.RESERVED, UUID.randomUUID(), now.plusSeconds(3600));

        Ticket availableTicket = createTicket(TicketStatus.AVAILABLE, null, null);

        assertThat(ticketRepository.count()).isEqualTo(3);

        // When
        int released = expirationService.releaseExpiredReservations();

        // Then
        assertThat(released).as("Only expired reservations must be released").isEqualTo(1);

        assertTicketAvailable(expiredTicket.getId());

        assertTicketReserved(activeTicket.getId(), activeTicket.getReservationId(), activeTicket.getReservedUntil());

        assertTicketAvailable(availableTicket.getId());
    }

    private void assertTicketAvailable(UUID ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.AVAILABLE);

        assertThat(ticket.getReservationId()).isNull();

        assertThat(ticket.getReservedUntil()).isNull();
    }

    private void assertTicketReserved(UUID ticketId, UUID reservationId, Instant reservedUntil) {

        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RESERVED);

        assertThat(ticket.getReservationId()).isEqualTo(reservationId);

        assertThat(ticket.getReservedUntil()).isCloseTo(reservedUntil, within(1, ChronoUnit.MICROS));
    }

    private Ticket createTicket(TicketStatus status, UUID reservationId, Instant reservedUntil) {
        Event event = new Event();
        event.setTitle("Expiration test event");
        event.setDescription("Event for reservation expiration tests");
        event.setEventDate(Instant.now().plusSeconds(3600));
        event.setVenue("Test venue");

        Event savedEvent = eventRepository.saveAndFlush(event);

        Ticket ticket = new Ticket();
        ticket.setEvent(savedEvent);
        ticket.setSeatNumber("A-" + UUID.randomUUID());
        ticket.setPrice(new BigDecimal("100.00"));
        ticket.setStatus(status);
        ticket.setReservationId(reservationId);
        ticket.setReservedUntil(truncateToMicros(reservedUntil));

        return ticketRepository.saveAndFlush(ticket);
    }

    private Instant truncateToMicros(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
