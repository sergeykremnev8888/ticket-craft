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

import ru.ticketcraft.client.CatalogClient;
import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.model.IdempotencyKey;
import ru.ticketcraft.model.IdempotencyStatus;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.OrderRepository;

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
    private CatalogClient catalogClient;

    @Mock
    private OutboxService outboxService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private RequestHashService requestHashService;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, catalogClient, outboxService, idempotencyService,
                requestHashService);
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

        verify(catalogClient, never()).reserveTicket(any());

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

        verify(catalogClient, never()).reserveTicket(any());
    }

    @Test
    void shouldCreateOrderForNewIdempotencyKey() {
        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_KEY, USER_ID, "hash", null, IdempotencyStatus.IN_PROGRESS,
                Instant.now());

        Order savedOrder = new Order(42L, USER_ID, EVENT_ID, TICKET_ID, PRICE, OrderState.CREATED, Instant.now());

        when(requestHashService.hash(any())).thenReturn("hash");

        when(idempotencyService.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, "hash")).thenReturn(key);

        when(catalogClient.reserveTicket(TICKET_ID)).thenReturn(true);

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        Order result = service.createOrder(IDEMPOTENCY_KEY, USER_ID, EVENT_ID, TICKET_ID, PRICE);

        assertSame(savedOrder, result);

        verify(catalogClient).reserveTicket(TICKET_ID);

        verify(orderRepository).save(any(Order.class));

        verify(idempotencyService).complete(IDEMPOTENCY_KEY, 42L);

        verify(outboxService).saveOrderCreatedEvent(eq(savedOrder), any(OrderEvent.class));
    }

}
