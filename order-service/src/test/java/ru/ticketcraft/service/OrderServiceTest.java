package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.exception.InvalidOrderStateTransitionException;
import ru.ticketcraft.exception.OrderNotFoundException;
import ru.ticketcraft.idempotency.CanonicalOrderRequest;
import ru.ticketcraft.model.IdempotencyKey;
import ru.ticketcraft.model.IdempotencyStatus;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.OrderRepository;
import ru.ticketcraft.repository.OrderSagaRepository;
import ru.ticketcraft.saga.OrderSagaStatus;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final String IDEMPOTENCY_KEY = "test-idempotency-key";
    private static final String REQUEST_HASH = "test-request-hash";

    private static final Long ORDER_ID = 123L;
    private static final Long USER_ID = 10L;
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final BigDecimal PRICE = new BigDecimal("100.00");
    private static final Instant CREATED_AT = Instant.parse("2026-09-10T12:00:00Z");

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

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, outboxService, idempotencyService, requestHashService,
                orderStateMachine, orderSagaRepository);
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
                .isInstanceOf(OrderNotFoundException.class).hasMessage("Order not found: " + ORDER_ID);

        verify(orderStateMachine, never()).validateTransition(any(), any());

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void shouldCreateOrderAndStartReservationSaga() {
        IdempotencyKey idempotencyKey = new IdempotencyKey(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH, null,
                IdempotencyStatus.IN_PROGRESS, CREATED_AT);

        Order savedOrder = new Order(ORDER_ID, USER_ID, EVENT_ID, TICKET_ID, PRICE, OrderState.CREATED, CREATED_AT);

        when(requestHashService.hash(any(CanonicalOrderRequest.class))).thenReturn(REQUEST_HASH);

        when(idempotencyService.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH)).thenReturn(idempotencyKey);

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        when(orderSagaRepository.insertIfAbsent(any(UUID.class), eq(ORDER_ID),
                eq(OrderSagaStatus.WAITING_FOR_RESERVATION.name()), any(Instant.class), any(Instant.class)))
                .thenReturn(1);

        Order result = orderService.createOrder(IDEMPOTENCY_KEY, USER_ID, EVENT_ID, TICKET_ID, PRICE);

        assertThat(result).isSameAs(savedOrder);

        ArgumentCaptor<UUID> sagaIdCaptor = ArgumentCaptor.forClass(UUID.class);

        verify(orderSagaRepository).insertIfAbsent(sagaIdCaptor.capture(), eq(ORDER_ID),
                eq(OrderSagaStatus.WAITING_FOR_RESERVATION.name()), any(Instant.class), any(Instant.class));

        ArgumentCaptor<ReserveTicketCommand> commandCaptor = ArgumentCaptor.forClass(ReserveTicketCommand.class);

        verify(outboxService).saveReserveTicketCommand(eq(savedOrder), commandCaptor.capture());

        UUID sagaId = sagaIdCaptor.getValue();
        ReserveTicketCommand command = commandCaptor.getValue();

        assertThat(command.orderId()).isEqualTo(ORDER_ID);
        assertThat(command.ticketId()).isEqualTo(TICKET_ID);
        assertThat(command.userId()).isEqualTo(USER_ID);
        assertThat(command.reservationId()).isEqualTo(sagaId);
        assertThat(command.messageId()).isEqualTo("saga:" + sagaId + ":reserve-ticket");

        verify(idempotencyService).complete(IDEMPOTENCY_KEY, ORDER_ID);
    }

    @Test
    void shouldFailOrderCreationWhenSagaCannotBeCreated() {
        IdempotencyKey processingKey = new IdempotencyKey(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH, null,
                IdempotencyStatus.IN_PROGRESS, CREATED_AT);

        Order savedOrder = new Order(ORDER_ID, USER_ID, EVENT_ID, TICKET_ID, PRICE, OrderState.CREATED, CREATED_AT);

        when(requestHashService.hash(any(CanonicalOrderRequest.class))).thenReturn(REQUEST_HASH);

        when(idempotencyService.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH)).thenReturn(processingKey);

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        when(orderSagaRepository.insertIfAbsent(any(UUID.class), eq(ORDER_ID),
                eq(OrderSagaStatus.WAITING_FOR_RESERVATION.name()), any(Instant.class), any(Instant.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> orderService.createOrder(IDEMPOTENCY_KEY, USER_ID, EVENT_ID, TICKET_ID, PRICE))
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to create saga for order: " + ORDER_ID);

        verify(outboxService, never()).saveReserveTicketCommand(any(), any());

        verify(idempotencyService, never()).complete(anyString(), anyLong());
    }

    private Order createOrder(OrderState state) {
        return new Order(ORDER_ID, USER_ID, EVENT_ID, TICKET_ID, PRICE, state, Instant.now());
    }
}