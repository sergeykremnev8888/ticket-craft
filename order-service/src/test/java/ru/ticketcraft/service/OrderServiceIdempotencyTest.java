package ru.ticketcraft.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.model.IdempotencyKey;
import ru.ticketcraft.model.IdempotencyStatus;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.OrderRepository;
import ru.ticketcraft.repository.OrderSagaRepository;
import ru.ticketcraft.saga.OrderSagaStatus;

@ExtendWith(MockitoExtension.class)
class OrderServiceIdempotencyTest {

    private static final String IDEMPOTENCY_KEY = "order-key";
    private static final Long USER_ID = 100L;

    private final UUID EVENT_ID = UUID.randomUUID();
    private final UUID TICKET_ID = UUID.randomUUID();
    private final BigDecimal PRICE = new BigDecimal("100.00");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private RequestHashService requestHashService;

    @Mock
    private OrderStateMachine orderStateMachine;

    @Mock
    private OrderSagaRepository orderSagaRepository;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, outboxService, idempotencyService, requestHashService,
                orderStateMachine, orderSagaRepository);
    }

    @Test
    void shouldReturnExistingOrderForCompletedIdempotencyKey() {
        Long orderId = 42L;

        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_KEY, USER_ID, "hash", orderId, IdempotencyStatus.COMPLETED,
                Instant.now());

        Order existingOrder = new Order(orderId, USER_ID, EVENT_ID, TICKET_ID, PRICE, OrderState.CREATED,
                Instant.now());

        when(requestHashService.hash(any())).thenReturn("hash");

        when(idempotencyService.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, "hash")).thenReturn(key);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));

        Order result = service.createOrder(IDEMPOTENCY_KEY, USER_ID, EVENT_ID, TICKET_ID, PRICE);

        assertSame(existingOrder, result);

        verify(orderRepository, never()).save(any());

        verify(outboxService, never()).saveOrderCreatedEvent(any(), any());
    }

    @Test
    void shouldThrowWhenCompletedKeyPointsToMissingOrder() {
        Long orderId = 42L;

        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_KEY, USER_ID, "hash", orderId, IdempotencyStatus.COMPLETED,
                Instant.now());

        when(requestHashService.hash(any())).thenReturn("hash");

        when(idempotencyService.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, "hash")).thenReturn(key);

        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service.createOrder(IDEMPOTENCY_KEY, USER_ID, EVENT_ID, TICKET_ID, PRICE));
    }

    @Test
    void shouldCreateOrderForNewIdempotencyKey() {

        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_KEY, USER_ID, "hash", null, IdempotencyStatus.IN_PROGRESS,
                Instant.now());

        Order savedOrder = new Order(42L, USER_ID, EVENT_ID, TICKET_ID, PRICE, OrderState.CREATED, Instant.now());

        when(requestHashService.hash(any())).thenReturn("hash");

        when(idempotencyService.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, "hash")).thenReturn(key);

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        when(orderSagaRepository.insertIfAbsent(any(UUID.class), eq(42L),
                eq(OrderSagaStatus.WAITING_FOR_RESERVATION.name()), any(Instant.class), any(Instant.class)))
                .thenReturn(1);

        Order result = service.createOrder(IDEMPOTENCY_KEY, USER_ID, EVENT_ID, TICKET_ID, PRICE);

        assertSame(savedOrder, result);

        verify(orderRepository).save(any(Order.class));

        verify(orderSagaRepository).insertIfAbsent(any(UUID.class), eq(42L),
                eq(OrderSagaStatus.WAITING_FOR_RESERVATION.name()), any(Instant.class), any(Instant.class));

        verify(idempotencyService).complete(IDEMPOTENCY_KEY, 42L);

        verify(outboxService).saveReserveTicketCommand(eq(savedOrder), any(ReserveTicketCommand.class));

        verify(outboxService, never()).saveOrderCreatedEvent(any(Order.class), any(OrderEvent.class));
    }

}
