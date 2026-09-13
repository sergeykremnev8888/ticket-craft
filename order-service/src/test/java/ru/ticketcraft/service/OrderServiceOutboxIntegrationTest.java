package ru.ticketcraft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.model.IdempotencyKey;
import ru.ticketcraft.model.IdempotencyStatus;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.model.OutboxEvent;
import ru.ticketcraft.model.OutboxStatus;
import ru.ticketcraft.repository.IdempotencyKeyRepository;
import ru.ticketcraft.repository.OrderRepository;
import ru.ticketcraft.repository.OrderSagaRepository;
import ru.ticketcraft.repository.OutboxEventRepository;
import ru.ticketcraft.saga.OrderSaga;
import ru.ticketcraft.saga.OrderSagaStatus;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = { 
        "ticketcraft.outbox.publisher.enabled=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.enabled=false"
})
class OrderServiceOutboxIntegrationTest {

    private static final String IDEMPOTENCY_KEY = "order-service-outbox-integration-key";
    private static final Long USER_ID = 123L;
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final BigDecimal PRICE = new BigDecimal("100.00");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("order_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderSagaRepository orderSagaRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {

        outboxEventRepository.deleteAll();
        orderSagaRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    void shouldCreateOrderSagaAndReserveTicketCommandInSameTransaction() throws Exception {

        Order order = orderService.createOrder(IDEMPOTENCY_KEY, USER_ID, EVENT_ID, TICKET_ID, PRICE);

        /*
         * Order.
         */
        assertNotNull(order.getId());

        assertEquals(USER_ID, order.getUserId());

        assertEquals(EVENT_ID, order.getEventId());

        assertEquals(TICKET_ID, order.getTicketId());

        assertEquals(0, PRICE.compareTo(order.getTotalPrice()));

        assertEquals(OrderState.CREATED, order.getStatus());

        /*
         * Order persisted.
         */
        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertEquals(order.getId(), persistedOrder.getId());

        assertEquals(OrderState.CREATED, persistedOrder.getStatus());

        /*
         * Saga.
         */
        OrderSaga saga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertNotNull(saga.getId());

        assertEquals(order.getId(), saga.getOrderId());

        assertEquals(OrderSagaStatus.WAITING_FOR_RESERVATION, saga.getStatus());

        /*
         * Idempotency.
         */
        IdempotencyKey idempotencyKey = idempotencyKeyRepository.findById(IDEMPOTENCY_KEY).orElseThrow();

        assertEquals(IdempotencyStatus.COMPLETED, idempotencyKey.getStatus());

        assertEquals(order.getId(), idempotencyKey.getOrderId());

        /*
         * Outbox.
         */
        List<OutboxEvent> outboxEvents = toList(outboxEventRepository.findAll());

        assertEquals(1, outboxEvents.size());

        OutboxEvent outboxEvent = outboxEvents.get(0);

        assertNotNull(outboxEvent.getId());

        assertEquals("ORDER", outboxEvent.getAggregateType());

        assertEquals(order.getId().toString(), outboxEvent.getAggregateId());

        assertEquals("ReserveTicket", outboxEvent.getEventType());

        assertEquals("ticket-reservation-commands", outboxEvent.getTopic());

        assertEquals(OutboxStatus.PENDING, outboxEvent.getStatus());

        assertNotNull(outboxEvent.getPayload());

        /*
         * Payload.
         */
        ReserveTicketCommand command = objectMapper.readValue(outboxEvent.getPayload(), ReserveTicketCommand.class);

        assertEquals(order.getId(), command.orderId());

        assertEquals(TICKET_ID, command.ticketId());

        assertEquals(USER_ID, command.userId());

        assertNotNull(command.reservationId());

        /*
         * Главный Saga invariant:
         *
         * order_sagas.id == ReserveTicketCommand.reservationId
         */
        assertEquals(saga.getId(), command.reservationId());

        /*
         * Deterministic logical message ID.
         */
        assertEquals("saga:" + saga.getId() + ":reserve-ticket", command.messageId());
    }

    private List<OutboxEvent> toList(Iterable<OutboxEvent> events) {

        return java.util.stream.StreamSupport.stream(events.spliterator(), false).toList();
    }
}