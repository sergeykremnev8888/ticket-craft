package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private KafkaTopicsProperties topics;
    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        topics = new KafkaTopicsProperties("order-events", "ticket-reservation-commands", "ticket-reservation-results",
                "ticket-reservation-results.DLT", "payment-requests", "payment-results", "payment-results.DLT");

        outboxService = new OutboxService(repository, objectMapper, topics);
    }

    @Test
    void shouldSaveOrderCreatedEvent() throws Exception {
        OrderEvent event = new OrderEvent(EVENT_ID.toString(), ORDER_ID, USER_ID, EVENT_ID, List.of(TICKET_ID),
                TOTAL_PRICE, OrderState.CREATED, CREATED_AT);

        Order order = new Order(ORDER_ID, USER_ID, EVENT_ID, TICKET_ID, TOTAL_PRICE, OrderState.CREATED, CREATED_AT);

        String payload = "{\"messageId\":\"11111111-1111-1111-1111-111111111111\"}";

        when(objectMapper.writeValueAsString(event)).thenReturn(payload);

        when(repository.insert(eq(EVENT_ID), eq("ORDER"), eq(ORDER_ID.toString()), eq("OrderCreated"),
                eq("order-events"), eq(payload), eq(CREATED_AT))).thenReturn(1);

        UUID result = outboxService.saveOrderCreatedEvent(order, event);

        assertEquals(EVENT_ID, result);

        verify(objectMapper).writeValueAsString(event);

        verify(repository).insert(eq(EVENT_ID), eq("ORDER"), eq(ORDER_ID.toString()), eq("OrderCreated"),
                eq("order-events"), eq(payload), eq(CREATED_AT));
    }

    @Test
    void shouldThrowExceptionWhenOutboxInsertFails() throws Exception {
        Order order = new Order(ORDER_ID, USER_ID, EVENT_ID, TICKET_ID, new BigDecimal("150.00"), OrderState.CREATED,
                CREATED_AT);

        OrderEvent event = new OrderEvent(UUID.randomUUID().toString(), ORDER_ID, USER_ID, EVENT_ID, List.of(TICKET_ID),
                new BigDecimal("150.00"), OrderState.CREATED, CREATED_AT);

        when(objectMapper.writeValueAsString(event)).thenReturn("{\"orderId\":123}");

        when(repository.insert(any(UUID.class), any(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> outboxService.saveOrderCreatedEvent(order, event))
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to insert outbox event: " + EVENT_ID);

        verify(repository).insert(any(UUID.class), eq("ORDER"), eq("123"), eq("OrderCreated"), eq("order-events"),
                eq("{\"orderId\":123}"), eq(CREATED_AT));
    }

    @Test
    void shouldThrowExceptionWhenEventSerializationFails() throws Exception {
        Long orderId = 123L;
        Long userId = 456L;
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        Order order = new Order(orderId, userId, eventId, ticketId, new BigDecimal("150.00"), OrderState.CREATED,
                createdAt);

        OrderEvent event = new OrderEvent(UUID.randomUUID().toString(), orderId, userId, eventId, List.of(ticketId),
                new BigDecimal("150.00"), OrderState.CREATED, createdAt);

        when(objectMapper.writeValueAsString(any(OrderEvent.class)))
                .thenThrow(new JacksonException("serialization failed") {
                });

        assertThatThrownBy(() -> outboxService.saveOrderCreatedEvent(order, event))
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to serialize outbox payload: OrderEvent")
                .hasCauseInstanceOf(JacksonException.class);

        verify(repository, never()).insert(any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any());
    }
}