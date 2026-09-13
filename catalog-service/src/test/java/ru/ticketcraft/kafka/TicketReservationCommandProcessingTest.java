package ru.ticketcraft.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.ticketcraft.config.ReservationProperties;
import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.TicketRepository;
import ru.ticketcraft.service.OutboxService;
import ru.ticketcraft.service.TicketReservationService;

@ExtendWith(MockitoExtension.class)
public class TicketReservationCommandProcessingTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private OutboxService outboxService;

    private ReservationProperties reservationProperties;
    private TicketReservationService service;

    @BeforeEach
    void setUp() {
        reservationProperties = new ReservationProperties(Duration.ofMinutes(10L));
        service = new TicketReservationService(ticketRepository, outboxService, reservationProperties);
    }

    @Test
    void shouldReserveTicketAndWriteSuccessOutbox() {

        UUID ticketId = UUID.randomUUID();

        UUID reservationId = UUID.randomUUID();

        ReserveTicketCommand command = new ReserveTicketCommand("saga:" + reservationId + ":reserve-ticket", 100L,
                reservationId, ticketId, 10L, Instant.now());

        when(ticketRepository.reserveTicket(eq(ticketId), eq(reservationId), any(Instant.class))).thenReturn(1);

        Ticket ticket = reservedTicket(ticketId, reservationId);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        when(outboxService.existsByMessageId("result:" + command.messageId())).thenReturn(false);

        service.processReserveTicketCommand(command);

        verify(outboxService).saveTicketReservedEvent(argThat(event -> event.orderId().equals(100L)
                && event.ticketId().equals(ticketId) && event.reservationId().equals(reservationId)
                && event.eventId().equals(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                && event.price().compareTo(new BigDecimal("150.00")) == 0
                && event.messageId().equals("result:" + command.messageId())));

        verify(outboxService, never()).saveTicketReservationFailedEvent(any());
    }

    @Test
    void shouldTreatSameReservationAsIdempotentSuccess() {

        UUID ticketId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        ReserveTicketCommand command = new ReserveTicketCommand("command-1", 100L, reservationId, ticketId, 10L,
                Instant.now());

        when(ticketRepository.reserveTicket(eq(ticketId), eq(reservationId), any(Instant.class))).thenReturn(0);

        Ticket ticket = reservedTicket(ticketId, reservationId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        service.processReserveTicketCommand(command);

        verify(outboxService).saveTicketReservedEvent(any());

        verify(outboxService, never()).saveTicketReservationFailedEvent(any());
    }

    @Test
    void shouldWriteFailureOutboxWhenTicketBelongsToAnotherReservation() {

        UUID ticketId = UUID.randomUUID();

        UUID existingReservationId = UUID.randomUUID();

        UUID requestedReservationId = UUID.randomUUID();

        ReserveTicketCommand command = new ReserveTicketCommand("command-1", 100L, requestedReservationId, ticketId,
                10L, Instant.now());

        when(ticketRepository.reserveTicket(eq(ticketId), eq(requestedReservationId), any(Instant.class)))
                .thenReturn(0);

        Ticket ticket = new Ticket();

        ticket.setId(ticketId);
        ticket.setStatus(TicketStatus.RESERVED);
        ticket.setReservationId(existingReservationId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        service.processReserveTicketCommand(command);

        verify(outboxService).saveTicketReservationFailedEvent(
                argThat(event -> event.reason().equals("TICKET_ALREADY_RESERVED") && event.orderId().equals(100L)
                        && event.ticketId().equals(ticketId) && event.reservationId().equals(requestedReservationId)));

        verify(outboxService, never()).saveTicketReservedEvent(any());
    }

    @Test
    void shouldWriteFailureOutboxWhenTicketDoesNotExist() {

        UUID ticketId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        ReserveTicketCommand command = new ReserveTicketCommand("command-1", 100L, reservationId, ticketId, 10L,
                Instant.now());

        when(ticketRepository.reserveTicket(eq(ticketId), eq(reservationId), any(Instant.class))).thenReturn(0);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

        service.processReserveTicketCommand(command);

        verify(outboxService)
                .saveTicketReservationFailedEvent(argThat(event -> event.reason().equals("TICKET_NOT_FOUND")));

        verify(outboxService, never()).saveTicketReservedEvent(any());
    }

    private static Ticket reservedTicket(UUID ticketId, UUID reservationId) {
        Event event = new Event();
        event.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

        Ticket ticket = new Ticket();
        ticket.setId(ticketId);
        ticket.setEvent(event);
        ticket.setPrice(new BigDecimal("150.00"));
        ticket.setStatus(TicketStatus.RESERVED);
        ticket.setReservationId(reservationId);

        return ticket;
    }
}
