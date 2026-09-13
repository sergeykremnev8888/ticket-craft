package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;

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
class OrderSagaCreationIntegrationTest {

    private static final String IDEMPOTENCY_KEY = "order-saga-integration-key";

    private static final Long USER_ID = 10L;

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
    void shouldCreateOrderSagaAndReservationCommandAtomically() throws Exception {

        Order result = orderService.createOrder(IDEMPOTENCY_KEY, USER_ID, EVENT_ID, TICKET_ID, PRICE);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getUserId()).isEqualTo(USER_ID);
        assertThat(result.getEventId()).isEqualTo(EVENT_ID);
        assertThat(result.getTicketId()).isEqualTo(TICKET_ID);
        assertThat(result.getTotalPrice()).isEqualByComparingTo(PRICE);
        assertThat(result.getStatus()).isEqualTo(OrderState.CREATED);

        /*
         * 1. Order действительно записан.
         */
        Order persistedOrder = orderRepository.findById(result.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CREATED);

        /*
         * 2. Saga создана для этого order.
         */
        OrderSaga saga = orderSagaRepository.findByOrderId(result.getId()).orElseThrow();

        assertThat(saga.getId()).isNotNull();

        assertThat(saga.getOrderId()).isEqualTo(result.getId());

        assertThat(saga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_RESERVATION);

        /*
         * 3. HTTP idempotency завершена.
         */
        IdempotencyKey idempotencyKey = idempotencyKeyRepository.findById(IDEMPOTENCY_KEY).orElseThrow();

        assertThat(idempotencyKey.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);

        assertThat(idempotencyKey.getOrderId()).isEqualTo(result.getId());

        /*
         * 4. В outbox должна быть ровно одна команда.
         */
        List<OutboxEvent> outboxEvents = toList(outboxEventRepository.findAll());

        assertThat(outboxEvents).hasSize(1);

        OutboxEvent outboxEvent = outboxEvents.get(0);

        assertThat(outboxEvent.getAggregateType()).isEqualTo("ORDER");

        assertThat(outboxEvent.getAggregateId()).isEqualTo(result.getId().toString());

        assertThat(outboxEvent.getEventType()).isEqualTo("ReserveTicket");

        assertThat(outboxEvent.getTopic()).isEqualTo("ticket-reservation-commands");

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);

        /*
         * 5. Проверяем сам payload.
         */
        ReserveTicketCommand command = objectMapper.readValue(outboxEvent.getPayload(), ReserveTicketCommand.class);

        assertThat(command.orderId()).isEqualTo(result.getId());

        assertThat(command.ticketId()).isEqualTo(TICKET_ID);

        assertThat(command.userId()).isEqualTo(USER_ID);

        /*
         * Главный Saga invariant:
         *
         * order_sagas.id == reservationId в Kafka command.
         */
        assertThat(command.reservationId()).isEqualTo(saga.getId());

        assertThat(command.messageId()).isEqualTo("saga:" + saga.getId() + ":reserve-ticket");
    }

    private List<OutboxEvent> toList(Iterable<OutboxEvent> events) {

        return java.util.stream.StreamSupport.stream(events.spliterator(), false).toList();
    }
}