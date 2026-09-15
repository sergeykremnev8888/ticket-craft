package ru.ticketcraft.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.ticketcraft.dto.ConfirmTicketCommand;
import ru.ticketcraft.dto.ReleaseTicketCommand;
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

        ConsumerRecord<String, Object> record = new ConsumerRecord<>("ticket-reservation-commands", 0, 0L, "42",
                command);

        consumer.handle(record);

        verify(reservationService).processReserveTicketCommand(command);
    }

    @Test
    void shouldProcessConfirmTicketCommand() {

        ConfirmTicketCommand command = new ConfirmTicketCommand("saga:111:confirm-ticket", 42L, UUID.randomUUID(),
                UUID.randomUUID(), Instant.now());

        ConsumerRecord<String, Object> record = new ConsumerRecord<>("ticket-reservation-commands", 0, 0L, "42",
                command);

        consumer.handle(record);

        verify(reservationService).processConfirmTicketCommand(command);
    }

    @Test
    void shouldProcessReleaseTicketCommand() {

        ReleaseTicketCommand command = new ReleaseTicketCommand("saga:111:release-ticket", 42L, UUID.randomUUID(),
                UUID.randomUUID(), Instant.now());

        ConsumerRecord<String, Object> record = new ConsumerRecord<>("ticket-reservation-commands", 0, 0L, "42",
                command);

        consumer.handle(record);

        verify(reservationService).processReleaseTicketCommand(command);
    }

    @Test
    void shouldRejectUnsupportedCommand() {

        String command = "unsupported";

        ConsumerRecord<String, Object> record = new ConsumerRecord<>("ticket-reservation-commands", 0, 0L, "42",
                command);

        assertThatThrownBy(() -> consumer.handle(record)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported ticket reservation command type: " + String.class.getName());
    }

    @Test
    void shouldRejectNullPayload() {

        ConsumerRecord<String, Object> record = new ConsumerRecord<>("ticket-reservation-commands", 0, 0L, "42", null);

        assertThatThrownBy(() -> consumer.handle(record)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ticket reservation command payload must not be null");
    }
}