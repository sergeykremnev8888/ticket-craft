package ru.ticketcraft.kafka;

import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.service.TicketReservationService;

@ExtendWith(MockitoExtension.class)
class TicketReservationCommandConsumerTest {

    @Mock
    private TicketReservationService reservationService;

    private TicketReservationCommandConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TicketReservationCommandConsumer(reservationService);
    }

    @Test
    void shouldReserveTicketUsingReservationIdFromCommand() {

        UUID reservationId = UUID.randomUUID();

        UUID ticketId = UUID.randomUUID();

        ReserveTicketCommand command = new ReserveTicketCommand("saga:" + reservationId + ":reserve-ticket", 42L,
                reservationId, ticketId, 10L, Instant.now());

        consumer.handle(command);

        verify(reservationService).reserveTicket(ticketId, reservationId);
    }
}
