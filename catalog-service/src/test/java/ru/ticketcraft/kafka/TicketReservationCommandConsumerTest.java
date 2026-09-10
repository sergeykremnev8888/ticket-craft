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
    void shouldProcessReserveTicketCommand() {

        ReserveTicketCommand command = new ReserveTicketCommand("saga:111:reserve-ticket", 42L, UUID.randomUUID(),
                UUID.randomUUID(), 7L, Instant.now());

        consumer.handle(command);

        verify(reservationService).processReserveTicketCommand(command);
    }
}
