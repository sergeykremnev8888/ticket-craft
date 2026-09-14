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

    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
    private OrderSagaRepository orderSagaRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, outboxService, idempotencyService, requestHashService,
                orderSagaRepository);
    }

    @Test
    void shouldCreateOrderAndStartReservationSaga() {
        IdempotencyKey idempotencyKey = new IdempotencyKey(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH, null,
                IdempotencyStatus.IN_PROGRESS, CREATED_AT);

        Order savedOrder = new Order(ORDER_ID, USER_ID, null, TICKET_ID, null, OrderState.CREATED, CREATED_AT);

        when(requestHashService.hash(any(CanonicalOrderRequest.class))).thenReturn(REQUEST_HASH);

        when(idempotencyService.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH)).thenReturn(idempotencyKey);

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        when(orderSagaRepository.insertIfAbsent(any(UUID.class), eq(ORDER_ID),
                eq(OrderSagaStatus.WAITING_FOR_RESERVATION.name()), any(Instant.class), any(Instant.class)))
                .thenReturn(1);

        Order result = orderService.createOrder(IDEMPOTENCY_KEY, USER_ID, TICKET_ID);

        assertThat(result).isSameAs(savedOrder);

        /*
         * eventId и totalPrice больше не принимаются от клиента. Они будут заполнены
         * позже после TicketReservedEvent.
         */
        assertThat(result.getEventId()).isNull();

        assertThat(result.getTotalPrice()).isNull();

        assertThat(result.getTicketId()).isEqualTo(TICKET_ID);

        assertThat(result.getStatus()).isEqualTo(OrderState.CREATED);

        ArgumentCaptor<CanonicalOrderRequest> requestCaptor = ArgumentCaptor.forClass(CanonicalOrderRequest.class);

        verify(requestHashService).hash(requestCaptor.capture());

        CanonicalOrderRequest canonicalRequest = requestCaptor.getValue();

        assertThat(canonicalRequest.userId()).isEqualTo(USER_ID);

        assertThat(canonicalRequest.ticketId()).isEqualTo(TICKET_ID);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);

        verify(orderRepository).save(orderCaptor.capture());

        Order newOrder = orderCaptor.getValue();

        assertThat(newOrder.getUserId()).isEqualTo(USER_ID);

        assertThat(newOrder.getTicketId()).isEqualTo(TICKET_ID);

        assertThat(newOrder.getEventId()).isNull();

        assertThat(newOrder.getTotalPrice()).isNull();

        assertThat(newOrder.getStatus()).isEqualTo(OrderState.CREATED);

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

        assertThat(command.occurredAt()).isNotNull();

        verify(idempotencyService).complete(IDEMPOTENCY_KEY, ORDER_ID);
    }

    @Test
    void shouldFailOrderCreationWhenSagaCannotBeCreated() {
        IdempotencyKey processingKey = new IdempotencyKey(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH, null,
                IdempotencyStatus.IN_PROGRESS, CREATED_AT);

        Order savedOrder = new Order(ORDER_ID, USER_ID, null, TICKET_ID, null, OrderState.CREATED, CREATED_AT);

        when(requestHashService.hash(any(CanonicalOrderRequest.class))).thenReturn(REQUEST_HASH);

        when(idempotencyService.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH)).thenReturn(processingKey);

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        when(orderSagaRepository.insertIfAbsent(any(UUID.class), eq(ORDER_ID),
                eq(OrderSagaStatus.WAITING_FOR_RESERVATION.name()), any(Instant.class), any(Instant.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> orderService.createOrder(IDEMPOTENCY_KEY, USER_ID, TICKET_ID))
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to create saga for order: " + ORDER_ID);

        verify(outboxService, never()).saveReserveTicketCommand(any(), any());

        verify(idempotencyService, never()).complete(anyString(), anyLong());
    }

    @Test
    void shouldReturnExistingOrderForCompletedIdempotencyKey() {
        IdempotencyKey completedKey = new IdempotencyKey(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH, ORDER_ID,
                IdempotencyStatus.COMPLETED, CREATED_AT);

        Order existingOrder = new Order(ORDER_ID, USER_ID, null, TICKET_ID, null, OrderState.CREATED, CREATED_AT);

        when(requestHashService.hash(any(CanonicalOrderRequest.class))).thenReturn(REQUEST_HASH);

        when(idempotencyService.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH)).thenReturn(completedKey);

        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existingOrder));

        Order result = orderService.createOrder(IDEMPOTENCY_KEY, USER_ID, TICKET_ID);

        assertThat(result).isSameAs(existingOrder);

        verify(orderRepository, never()).save(any(Order.class));

        verify(orderSagaRepository, never()).insertIfAbsent(any(UUID.class), anyLong(), anyString(), any(Instant.class),
                any(Instant.class));

        verify(outboxService, never()).saveReserveTicketCommand(any(), any());

        verify(idempotencyService, never()).complete(anyString(), anyLong());
    }

    @Test
    void shouldReturnOrderWhenItBelongsToUser() {
        Order order = new Order(ORDER_ID, USER_ID, null, TICKET_ID, null, OrderState.CREATED, CREATED_AT);

        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));

        Order result = orderService.getOrder(ORDER_ID, USER_ID);

        assertThat(result).isSameAs(order);

        verify(orderRepository).findByIdAndUserId(ORDER_ID, USER_ID);
    }

    @Test
    void shouldThrowWhenOrderDoesNotBelongToUserOrDoesNotExist() {
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(ORDER_ID, USER_ID)).isInstanceOf(OrderNotFoundException.class);

        verify(orderRepository).findByIdAndUserId(ORDER_ID, USER_ID);
    }
}