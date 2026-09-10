package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.exception.TicketAlreadyReservedException;
import ru.ticketcraft.exception.TicketNotFoundException;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.TicketRepository;

@ExtendWith(MockitoExtension.class)
class TicketReservationServiceTest {

    private static final UUID TICKET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID RESERVATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID ANOTHER_RESERVATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private TicketRepository ticketRepository;

    private ReservationProperties reservationProperties;
    private TicketReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationProperties = new ReservationProperties(Duration.ofMinutes(10L));
        reservationService = new TicketReservationService(ticketRepository, reservationProperties);
    }

    @Test
    void shouldReserveAvailableTicketUsingProvidedReservationId() {

        when(ticketRepository.reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class))).thenReturn(1);

        assertThatCode(() -> reservationService.reserveTicket(TICKET_ID, RESERVATION_ID)).doesNotThrowAnyException();

        verify(ticketRepository).reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class));

        /*
         * UPDATE succeeded, поэтому дополнительный SELECT из БД нам не нужен.
         */
        verify(ticketRepository, never()).findById(TICKET_ID);
    }

    @Test
    void shouldTreatSameReservationAsIdempotentSuccess() {

        /*
         * UPDATE ... WHERE status = AVAILABLE ничего не обновил, потому что ticket уже
         * RESERVED.
         */
        when(ticketRepository.reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class))).thenReturn(0);

        Ticket ticket = createReservedTicket(TICKET_ID, RESERVATION_ID);

        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatCode(() -> reservationService.reserveTicket(TICKET_ID, RESERVATION_ID)).doesNotThrowAnyException();

        verify(ticketRepository).reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class));

        verify(ticketRepository).findById(TICKET_ID);
    }

    @Test
    void shouldRejectReservationOwnedByAnotherReservation() {

        when(ticketRepository.reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class))).thenReturn(0);

        Ticket ticket = createReservedTicket(TICKET_ID, ANOTHER_RESERVATION_ID);

        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> reservationService.reserveTicket(TICKET_ID, RESERVATION_ID))
                .isInstanceOf(TicketAlreadyReservedException.class);

        verify(ticketRepository).reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class));

        verify(ticketRepository).findById(TICKET_ID);
    }

    @Test
    void shouldThrowWhenTicketDoesNotExist() {

        when(ticketRepository.reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class))).thenReturn(0);

        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.reserveTicket(TICKET_ID, RESERVATION_ID))
                .isInstanceOf(TicketNotFoundException.class);

        verify(ticketRepository).reserveTicket(eq(TICKET_ID), eq(RESERVATION_ID), any(Instant.class));

        verify(ticketRepository).findById(TICKET_ID);
    }

    private Ticket createReservedTicket(UUID ticketId, UUID reservationId) {

        Ticket ticket = new Ticket();

        ticket.setId(ticketId);
        ticket.setStatus(TicketStatus.RESERVED);
        ticket.setReservationId(reservationId);

        return ticket;
    }
}