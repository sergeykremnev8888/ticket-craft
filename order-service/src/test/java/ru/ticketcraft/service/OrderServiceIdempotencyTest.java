package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private OrderRepository orderRepository;
    @Mock private OutboxService outboxService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private RequestHashService requestHashService;
    @Mock private OrderSagaRepository orderSagaRepository;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, outboxService, idempotencyService, requestHashService,
                orderSagaRepository);
    }

    @Test
    void shouldReturnExistingOrderForCompletedIdempotencyKey() {
        Long orderId = 42L;
        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_KEY, USER_ID, "hash", orderId,
                IdempotencyStatus.COMPLETED, Instant.now());
        Order existingOrder = new Order(orderId, USER_ID, null, TICKET_ID, null, OrderState.CREATED, Instant.now());

        when(requestHashService.hash(any())).thenReturn("hash");
        when(idempotencyService.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, "hash")).thenReturn(key);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));

        Order result = service.createOrder(IDEMPOTENCY_KEY, USER_ID, TICKET_ID);

        assertThat(result).isSameAs(existingOrder);
        verify(orderRepository, never()).save(any());
        verify(outboxService, never()).saveReserveTicketCommand(any(), any());
    }

    @Test
    void shouldThrowWhenCompletedKeyPointsToMissingOrder() {
        Long orderId = 42L;
        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_KEY, USER_ID, "hash", orderId,
                IdempotencyStatus.COMPLETED, Instant.now());

        when(requestHashService.hash(any())).thenReturn("hash");
        when(idempotencyService.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, "hash")).thenReturn(key);
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrder(IDEMPOTENCY_KEY, USER_ID, TICKET_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldCreatePendingOrderSagaAndReservationCommand() {
        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_KEY, USER_ID, "hash", null,
                IdempotencyStatus.IN_PROGRESS, Instant.now());
        Order savedOrder = new Order(42L, USER_ID, null, TICKET_ID, null, OrderState.CREATED, Instant.now());

        when(requestHashService.hash(any())).thenReturn("hash");
        when(idempotencyService.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, "hash")).thenReturn(key);
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        when(orderSagaRepository.insertIfAbsent(any(UUID.class), eq(42L),
                eq(OrderSagaStatus.WAITING_FOR_RESERVATION.name()), any(Instant.class), any(Instant.class)))
                .thenReturn(1);

        Order result = service.createOrder(IDEMPOTENCY_KEY, USER_ID, TICKET_ID);

        assertThat(result).isSameAs(savedOrder);
        verify(outboxService).saveReserveTicketCommand(eq(savedOrder), any(ReserveTicketCommand.class));
        verify(idempotencyService).complete(IDEMPOTENCY_KEY, 42L);
    }
}
