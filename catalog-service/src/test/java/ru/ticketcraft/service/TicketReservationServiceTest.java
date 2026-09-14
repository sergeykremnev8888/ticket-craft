package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.ticketcraft.config.ReservationProperties;
import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketReservedEvent;
import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.TicketRepository;

@ExtendWith(MockitoExtension.class)
class TicketReservationServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID RESERVATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID ANOTHER_RESERVATION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final Long ORDER_ID = 1001L;

    private static final Long USER_ID = 501L;

    private static final BigDecimal PRICE = new BigDecimal("100.00");

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private OutboxService outboxService;

    private TicketReservationService reservationService;

    @BeforeEach
    void setUp() {
        ReservationProperties reservationProperties = new ReservationProperties(Duration.ofMinutes(10L));

        reservationService = new TicketReservationService(ticketRepository, outboxService, reservationProperties);
    }

    @Test
    void shouldReserveAvailableTicketAndCreateSuccessResult() {
        ReserveTicketCommand command = createCommand(RESERVATION_ID);

        String expectedResultMessageId = resultMessageId(command);

        when(outboxService.existsByMessageId(expectedResultMessageId)).thenReturn(false);

        when(ticketRepository.reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class))).thenReturn(1);

        Ticket ticket = createReservedTicket(TICKET_ID, RESERVATION_ID);

        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        reservationService.processReserveTicketCommand(command);

        verify(outboxService).existsByMessageId(expectedResultMessageId);

        verify(ticketRepository).reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class));

        verify(ticketRepository).findById(TICKET_ID);

        ArgumentCaptor<TicketReservedEvent> eventCaptor = ArgumentCaptor.forClass(TicketReservedEvent.class);

        verify(outboxService).saveTicketReservedEvent(eventCaptor.capture());

        TicketReservedEvent event = eventCaptor.getValue();

        assertThat(event.messageId()).isEqualTo(expectedResultMessageId);

        assertThat(event.orderId()).isEqualTo(ORDER_ID);

        assertThat(event.reservationId()).isEqualTo(RESERVATION_ID);

        assertThat(event.ticketId()).isEqualTo(TICKET_ID);

        assertThat(event.eventId()).isEqualTo(EVENT_ID);

        assertThat(event.price()).isEqualByComparingTo(PRICE);

        assertThat(event.occurredAt()).isNotNull();

        verify(outboxService, never()).saveTicketReservationFailedEvent(any());

        verifyNoMoreInteractions(ticketRepository, outboxService);
    }

    @Test
    void shouldTreatSameReservationAsIdempotentSuccess() {
        ReserveTicketCommand command = createCommand(RESERVATION_ID);

        String expectedResultMessageId = resultMessageId(command);

        when(outboxService.existsByMessageId(expectedResultMessageId)).thenReturn(false);

        /*
         * Atomic UPDATE не сработал, потому что ticket уже RESERVED.
         */
        when(ticketRepository.reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class))).thenReturn(0);

        Ticket ticket = createReservedTicket(TICKET_ID, RESERVATION_ID);

        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        reservationService.processReserveTicketCommand(command);

        verify(outboxService).existsByMessageId(expectedResultMessageId);

        verify(ticketRepository).reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class));

        verify(ticketRepository).findById(TICKET_ID);

        ArgumentCaptor<TicketReservedEvent> eventCaptor = ArgumentCaptor.forClass(TicketReservedEvent.class);

        verify(outboxService).saveTicketReservedEvent(eventCaptor.capture());

        TicketReservedEvent event = eventCaptor.getValue();

        assertThat(event.messageId()).isEqualTo(expectedResultMessageId);

        assertThat(event.reservationId()).isEqualTo(RESERVATION_ID);

        assertThat(event.ticketId()).isEqualTo(TICKET_ID);

        assertThat(event.eventId()).isEqualTo(EVENT_ID);

        assertThat(event.price()).isEqualByComparingTo(PRICE);

        verify(outboxService, never()).saveTicketReservationFailedEvent(any());

        verifyNoMoreInteractions(ticketRepository, outboxService);
    }

    @Test
    void shouldCreateFailureResultWhenTicketBelongsToAnotherReservation() {
        ReserveTicketCommand command = createCommand(RESERVATION_ID);

        String expectedResultMessageId = resultMessageId(command);

        when(outboxService.existsByMessageId(expectedResultMessageId)).thenReturn(false);

        when(ticketRepository.reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class))).thenReturn(0);

        Ticket ticket = createReservedTicket(TICKET_ID, ANOTHER_RESERVATION_ID);

        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        reservationService.processReserveTicketCommand(command);

        verify(outboxService).existsByMessageId(expectedResultMessageId);

        verify(ticketRepository).reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class));

        verify(ticketRepository).findById(TICKET_ID);

        ArgumentCaptor<TicketReservationFailedEvent> eventCaptor = ArgumentCaptor
                .forClass(TicketReservationFailedEvent.class);

        verify(outboxService).saveTicketReservationFailedEvent(eventCaptor.capture());

        TicketReservationFailedEvent event = eventCaptor.getValue();

        assertThat(event.messageId()).isEqualTo(expectedResultMessageId);

        assertThat(event.orderId()).isEqualTo(ORDER_ID);

        assertThat(event.reservationId()).isEqualTo(RESERVATION_ID);

        assertThat(event.ticketId()).isEqualTo(TICKET_ID);

        assertThat(event.reason()).isEqualTo("TICKET_ALREADY_RESERVED");

        assertThat(event.occurredAt()).isNotNull();

        verify(outboxService, never()).saveTicketReservedEvent(any());

        verifyNoMoreInteractions(ticketRepository, outboxService);
    }

    @Test
    void shouldCreateFailureResultWhenTicketDoesNotExist() {
        ReserveTicketCommand command = createCommand(RESERVATION_ID);

        String expectedResultMessageId = resultMessageId(command);

        when(outboxService.existsByMessageId(expectedResultMessageId)).thenReturn(false);

        when(ticketRepository.reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class))).thenReturn(0);

        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        reservationService.processReserveTicketCommand(command);

        verify(outboxService).existsByMessageId(expectedResultMessageId);

        verify(ticketRepository).reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class));

        verify(ticketRepository).findById(TICKET_ID);

        ArgumentCaptor<TicketReservationFailedEvent> eventCaptor = ArgumentCaptor
                .forClass(TicketReservationFailedEvent.class);

        verify(outboxService).saveTicketReservationFailedEvent(eventCaptor.capture());

        TicketReservationFailedEvent event = eventCaptor.getValue();

        assertThat(event.messageId()).isEqualTo(expectedResultMessageId);

        assertThat(event.orderId()).isEqualTo(ORDER_ID);

        assertThat(event.reservationId()).isEqualTo(RESERVATION_ID);

        assertThat(event.ticketId()).isEqualTo(TICKET_ID);

        assertThat(event.reason()).isEqualTo("TICKET_NOT_FOUND");

        assertThat(event.occurredAt()).isNotNull();

        verify(outboxService, never()).saveTicketReservedEvent(any());

        verifyNoMoreInteractions(ticketRepository, outboxService);
    }

    @Test
    void shouldIgnoreCommandWhenResultAlreadyExists() {
        ReserveTicketCommand command = createCommand(RESERVATION_ID);

        String expectedResultMessageId = resultMessageId(command);

        when(outboxService.existsByMessageId(expectedResultMessageId)).thenReturn(true);

        reservationService.processReserveTicketCommand(command);

        verify(outboxService).existsByMessageId(expectedResultMessageId);

        /*
         * Result уже существует в outbox.
         *
         * Значит Kafka redelivery полностью игнорируется: ticket повторно не изменяем и
         * новый result event не создаём.
         */
        verifyNoMoreInteractions(outboxService);
        verifyNoMoreInteractions(ticketRepository);
    }

    private ReserveTicketCommand createCommand(UUID reservationId) {
        return new ReserveTicketCommand("saga:" + reservationId + ":reserve-ticket", ORDER_ID, reservationId, TICKET_ID,
                USER_ID, Instant.parse("2026-09-14T10:00:00Z"));
    }

    private String resultMessageId(ReserveTicketCommand command) {
        return "result:" + command.messageId();
    }

    private Ticket createReservedTicket(UUID ticketId, UUID reservationId) {
        Event event = new Event();

        event.setId(EVENT_ID);

        Ticket ticket = new Ticket();

        ticket.setId(ticketId);
        ticket.setEvent(event);
        ticket.setPrice(PRICE);
        ticket.setStatus(TicketStatus.RESERVED);
        ticket.setReservationId(reservationId);
        ticket.setReservedUntil(Instant.parse("2026-09-14T10:10:00Z"));

        return ticket;
    }
}
