package ru.ticketcraft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.OutboxEventRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxEventRepository repository;

    @Mock
    private ObjectMapper objectMapper;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new OutboxService(repository, objectMapper);
    }

    @Test
    void shouldSaveOrderCreatedEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        Long orderId = 123L;
        Long userId = 456L;
        UUID eventUuid = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        Order order = new Order(orderId, userId, eventUuid, ticketId, new BigDecimal("150.00"), OrderState.CREATED,
                createdAt);

        OrderEvent event = new OrderEvent(UUID.randomUUID().toString(), orderId, userId, eventUuid, List.of(ticketId),
                new BigDecimal("150.00"), OrderState.CREATED, createdAt);

        String payload = "{\"orderId\":123}";

        when(objectMapper.writeValueAsString(event)).thenReturn(payload);

        when(repository.insert(any(UUID.class), eq("ORDER"), eq("123"), eq("OrderCreated"), eq(payload), eq(createdAt)))
                .thenReturn(1);

        UUID result = outboxService.saveOrderCreatedEvent(order, event);

        assertEquals(eventId.getClass(), result.getClass());

        verify(objectMapper).writeValueAsString(event);

        verify(repository).insert(any(UUID.class), eq("ORDER"), eq("123"), eq("OrderCreated"), eq(payload),
                eq(createdAt));
    }

    @Test
    void shouldThrowExceptionWhenOutboxInsertFails() throws Exception {
        Long orderId = 123L;
        Long userId = 456L;
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        Order order = new Order(orderId, userId, eventId, ticketId, new BigDecimal("150.00"), OrderState.CREATED,
                createdAt);

        OrderEvent event = new OrderEvent(UUID.randomUUID().toString(), orderId, userId, eventId, List.of(ticketId),
                new BigDecimal("150.00"), OrderState.CREATED, createdAt);

        when(objectMapper.writeValueAsString(event)).thenReturn("{\"orderId\":123}");

        when(repository.insert(any(UUID.class), any(), any(), any(), any(), any())).thenReturn(0);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> outboxService.saveOrderCreatedEvent(order, event));

        assertEquals("Failed to insert outbox event for order: 123", exception.getMessage());

        verify(repository).insert(any(UUID.class), eq("ORDER"), eq("123"), eq("OrderCreated"), eq("{\"orderId\":123}"),
                eq(createdAt));
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

        when(objectMapper.writeValueAsString(event)).thenThrow(new JacksonException("Serialization failed") {
        });

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> outboxService.saveOrderCreatedEvent(order, event));

        assertEquals("Failed to serialize OrderEvent", exception.getMessage());
    }
}