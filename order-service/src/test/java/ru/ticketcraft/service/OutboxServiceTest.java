package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.ticketcraft.config.KafkaTopicsProperties;
import ru.ticketcraft.dto.ConfirmTicketCommand;
import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.OutboxEventRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    private static final Long ORDER_ID = 123L;
    private static final Long USER_ID = 10L;
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TICKET_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final BigDecimal TOTAL_PRICE = new BigDecimal("100.00");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private ObjectMapper objectMapper;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "order-events",
                "ticket-reservation-commands",
                "ticket-reservation-results", 
                "ticket-reservation-results.DLT", 
                "payment-requests",
                "payment-commands", 
                "payment-results",
                "payment-results.DLT");
        outboxService = new OutboxService(repository, objectMapper, topics);
    }

    @Test
    void shouldSaveOrderConfirmedEvent() throws Exception {
        OrderEvent event = new OrderEvent("saga:1:order-confirmed", ORDER_ID, USER_ID, EVENT_ID, List.of(TICKET_ID),
                TOTAL_PRICE, OrderState.CONFIRMED, CREATED_AT);
        Order order = new Order(ORDER_ID, USER_ID, EVENT_ID, TICKET_ID, TOTAL_PRICE, OrderState.CONFIRMED, CREATED_AT);
        String payload = "{\"messageId\":\"saga:1:order-confirmed\"}";

        when(objectMapper.writeValueAsString(event)).thenReturn(payload);
        when(repository.insert(any(UUID.class), eq("ORDER"), eq(ORDER_ID.toString()), eq("OrderConfirmed"),
                eq("order-events"), eq(payload), eq(CREATED_AT))).thenReturn(1);

        UUID result = outboxService.saveOrderConfirmedEvent(order, event);

        assertThat(result).isNotNull();
        verify(repository).insert(eq(result), eq("ORDER"), eq(ORDER_ID.toString()), eq("OrderConfirmed"),
                eq("order-events"), eq(payload), eq(CREATED_AT));
    }

    @Test
    void shouldThrowWhenOutboxInsertFails() throws Exception {
        OrderEvent event = new OrderEvent("saga:1:order-confirmed", ORDER_ID, USER_ID, EVENT_ID, List.of(TICKET_ID),
                TOTAL_PRICE, OrderState.CONFIRMED, CREATED_AT);
        Order order = new Order(ORDER_ID, USER_ID, EVENT_ID, TICKET_ID, TOTAL_PRICE, OrderState.CONFIRMED, CREATED_AT);

        when(objectMapper.writeValueAsString(event)).thenReturn("{\"orderId\":123}");
        when(repository.insert(any(UUID.class), any(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> outboxService.saveOrderConfirmedEvent(order, event))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("Failed to insert outbox event: ");
    }

    @Test
    void shouldThrowWhenSerializationFails() throws Exception {
        OrderEvent event = new OrderEvent("saga:1:order-confirmed", ORDER_ID, USER_ID, EVENT_ID, List.of(TICKET_ID),
                TOTAL_PRICE, OrderState.CONFIRMED, CREATED_AT);
        Order order = new Order(ORDER_ID, USER_ID, EVENT_ID, TICKET_ID, TOTAL_PRICE, OrderState.CONFIRMED, CREATED_AT);

        when(objectMapper.writeValueAsString(any(OrderEvent.class)))
                .thenThrow(new JacksonException("serialization failed") {
                });

        assertThatThrownBy(() -> outboxService.saveOrderConfirmedEvent(order, event))
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to serialize outbox payload: OrderEvent")
                .hasCauseInstanceOf(JacksonException.class);

        verify(repository, never()).insert(any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any());
    }

    @Test
    void shouldSaveConfirmTicketCommand() throws Exception {

        UUID reservationId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        ConfirmTicketCommand command = new ConfirmTicketCommand("saga:" + reservationId + ":confirm-ticket", ORDER_ID,
                reservationId, TICKET_ID, CREATED_AT);

        Order order = new Order(ORDER_ID, USER_ID, EVENT_ID, TICKET_ID, TOTAL_PRICE, OrderState.PAYMENT_PENDING,
                CREATED_AT);

        String payload = "{\"messageId\":\"saga:" + reservationId + ":confirm-ticket\"}";

        when(objectMapper.writeValueAsString(command)).thenReturn(payload);

        when(repository.insert(any(UUID.class), eq("ORDER"), eq(ORDER_ID.toString()), eq("ConfirmTicket"),
                eq("ticket-reservation-commands"), eq(payload), eq(CREATED_AT))).thenReturn(1);

        UUID result = outboxService.saveConfirmTicketCommand(order, command);

        assertThat(result).isNotNull();

        verify(repository).insert(eq(result), eq("ORDER"), eq(ORDER_ID.toString()), eq("ConfirmTicket"),
                eq("ticket-reservation-commands"), eq(payload), eq(CREATED_AT));
    }
}
