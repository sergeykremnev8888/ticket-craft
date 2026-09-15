package ru.ticketcraft.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.ticketcraft.dto.TicketConfirmedEvent;
import ru.ticketcraft.dto.TicketReleasedEvent;
import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketReservedEvent;
import ru.ticketcraft.service.TicketReservationResultProcessor;

@ExtendWith(MockitoExtension.class)
class TicketReservationResultConsumerTest {

    private static final UUID RESERVATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final Long ORDER_ID = 42L;

    @Mock
    private TicketReservationResultProcessor processor;

    private TicketReservationResultConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TicketReservationResultConsumer(processor);
    }

    @Test
    void shouldProcessTicketReservedEvent() {

        TicketReservedEvent event = new TicketReservedEvent("result:saga:" + RESERVATION_ID + ":reserve-ticket",
                ORDER_ID, RESERVATION_ID, TICKET_ID, EVENT_ID, new BigDecimal("100.00"), Instant.now());

        consumer.handle(record(event));

        verify(processor).process(event);
    }

    @Test
    void shouldProcessTicketReservationFailedEvent() {

        TicketReservationFailedEvent event = new TicketReservationFailedEvent(
                "result:saga:" + RESERVATION_ID + ":reserve-ticket", ORDER_ID, RESERVATION_ID, TICKET_ID,
                "TICKET_ALREADY_RESERVED", Instant.now());

        consumer.handle(record(event));

        verify(processor).process(event);
    }

    @Test
    void shouldProcessTicketConfirmedEvent() {

        TicketConfirmedEvent event = new TicketConfirmedEvent("result:saga:" + RESERVATION_ID + ":confirm-ticket",
                ORDER_ID, RESERVATION_ID, TICKET_ID, Instant.now());

        consumer.handle(record(event));

        verify(processor).process(event);
    }

    @Test
    void shouldProcessTicketReleasedEvent() {

        TicketReleasedEvent event = new TicketReleasedEvent("result:saga:" + RESERVATION_ID + ":release-ticket",
                ORDER_ID, RESERVATION_ID, TICKET_ID, Instant.now());

        consumer.handle(record(event));

        verify(processor).process(event);
    }

    @Test
    void shouldRejectUnsupportedEvent() {

        ConsumerRecord<String, Object> record = record("unsupported");

        assertThatThrownBy(() -> consumer.handle(record)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported ticket reservation result event type: " + String.class.getName());
    }

    @Test
    void shouldRejectNullPayload() {

        ConsumerRecord<String, Object> record = record(null);

        assertThatThrownBy(() -> consumer.handle(record)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ticket reservation result payload must not be null");
    }

    private ConsumerRecord<String, Object> record(Object value) {

        return new ConsumerRecord<>("ticket-reservation-results", 0, 0L, ORDER_ID.toString(), value);
    }
}