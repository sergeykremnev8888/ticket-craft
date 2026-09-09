package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import ru.ticketcraft.client.CatalogClient;
import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.exception.InvalidOrderStateTransitionException;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.OrderRepository;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Long ORDER_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID TICKET_ID = UUID.randomUUID();
    private static final BigDecimal PRICE = new BigDecimal("100.00");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CatalogClient catalogClient;

    @Mock
    private OutboxService outboxService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private RequestHashService requestHashService;

    @Mock
    private OrderStateMachine orderStateMachine;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, catalogClient, outboxService, idempotencyService,
                requestHashService, orderStateMachine);
    }

    @Test
    void shouldTransitionOrderToTargetState() {
        Order order = createOrder(OrderState.CREATED);

        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        when(orderRepository.save(order)).thenReturn(order);

        Order result = orderService.transitionTo(ORDER_ID, OrderState.TICKETS_RESERVED);

        verify(orderStateMachine).validateTransition(OrderState.CREATED, OrderState.TICKETS_RESERVED);

        verify(orderRepository).save(order);

        assertThat(result.getStatus()).isEqualTo(OrderState.TICKETS_RESERVED);
    }

    @Test
    void shouldRejectInvalidStateTransition() {
        Order order = createOrder(OrderState.CREATED);

        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        org.mockito.Mockito
                .doThrow(new InvalidOrderStateTransitionException(
                        "Invalid order state transition: CREATED -> CONFIRMED"))
                .when(orderStateMachine).validateTransition(OrderState.CREATED, OrderState.CONFIRMED);

        assertThatThrownBy(() -> orderService.transitionTo(ORDER_ID, OrderState.CONFIRMED))
                .isInstanceOf(InvalidOrderStateTransitionException.class)
                .hasMessage("Invalid order state transition: CREATED -> CONFIRMED");

        verify(orderStateMachine).validateTransition(OrderState.CREATED, OrderState.CONFIRMED);

        verify(orderRepository, never()).save(any(Order.class));

        assertThat(order.getStatus()).isEqualTo(OrderState.CREATED);
    }

    @Test
    void shouldThrowWhenOrderDoesNotExist() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.transitionTo(ORDER_ID, OrderState.TICKETS_RESERVED))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Order not found: " + ORDER_ID);

        verify(orderStateMachine, never()).validateTransition(any(), any());

        verify(orderRepository, never()).save(any(Order.class));
    }

    private Order createOrder(OrderState state) {
        return new Order(ORDER_ID, USER_ID, EVENT_ID, TICKET_ID, PRICE, state, Instant.now());
    }
}