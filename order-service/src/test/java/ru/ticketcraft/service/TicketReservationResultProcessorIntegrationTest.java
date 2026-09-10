package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketReservedEvent;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.model.OutboxEvent;
import ru.ticketcraft.model.OutboxStatus;
import ru.ticketcraft.repository.OrderRepository;
import ru.ticketcraft.repository.OrderSagaRepository;
import ru.ticketcraft.repository.OutboxEventRepository;
import ru.ticketcraft.saga.OrderSaga;
import ru.ticketcraft.saga.OrderSagaStatus;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = { "ticketcraft.outbox.publisher.enabled=false" })
class TicketReservationResultProcessorIntegrationTest {

    private static final Long USER_ID = 10L;

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID RESERVATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final BigDecimal PRICE = new BigDecimal("100.00");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("order_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private TicketReservationResultProcessor processor;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderSagaRepository orderSagaRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {

        outboxEventRepository.deleteAll();

        jdbcTemplate.update("DELETE FROM processed_events");

        orderSagaRepository.deleteAll();

        orderRepository.deleteAll();
    }

    @Test
    void shouldMoveOrderToPaymentPendingAndCreatePaymentRequestWhenTicketReserved() throws Exception {

        Order order = createOrder(OrderState.CREATED);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_RESERVATION);

        String messageId = "result:saga:" + RESERVATION_ID + ":reserve-ticket";

        TicketReservedEvent event = new TicketReservedEvent(messageId, order.getId(), RESERVATION_ID, TICKET_ID,
                Instant.now());

        processor.process(event);

        /*
         * Order:
         *
         * CREATED -> TICKETS_RESERVED -> PAYMENT_PENDING
         */
        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.PAYMENT_PENDING);

        /*
         * Saga:
         *
         * WAITING_FOR_RESERVATION -> WAITING_FOR_PAYMENT
         */
        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_PAYMENT);

        /*
         * Consumer idempotency marker должен быть сохранён.
         */
        assertThat(processedEventCount(messageId)).isEqualTo(1);

        /*
         * Должен появиться ровно один payment request.
         */
        List<OutboxEvent> outboxEvents = toList(outboxEventRepository.findAll());

        assertThat(outboxEvents).hasSize(1);

        OutboxEvent outboxEvent = outboxEvents.get(0);

        assertThat(outboxEvent.getAggregateType()).isEqualTo("ORDER");

        assertThat(outboxEvent.getAggregateId()).isEqualTo(order.getId().toString());

        assertThat(outboxEvent.getEventType()).isEqualTo("PaymentRequested");

        assertThat(outboxEvent.getTopic()).isEqualTo("payment-requests");

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);

        PaymentRequestedEvent paymentRequestedEvent = objectMapper.readValue(outboxEvent.getPayload(),
                PaymentRequestedEvent.class);

        assertThat(paymentRequestedEvent.orderId()).isEqualTo(order.getId());

        assertThat(paymentRequestedEvent.userId()).isEqualTo(USER_ID);

        assertThat(paymentRequestedEvent.amount()).isEqualByComparingTo(PRICE);

        assertThat(paymentRequestedEvent.messageId()).isEqualTo("saga:" + RESERVATION_ID + ":payment-requested");
    }

    @Test
    void shouldCancelOrderAndFailSagaWhenTicketReservationFails() {

        Order order = createOrder(OrderState.CREATED);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_RESERVATION);

        String messageId = "result:saga:" + RESERVATION_ID + ":reserve-ticket";

        TicketReservationFailedEvent event = new TicketReservationFailedEvent(messageId, order.getId(), RESERVATION_ID,
                TICKET_ID, "TICKET_ALREADY_RESERVED", Instant.now());

        processor.process(event);

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CANCELED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.FAILED);

        assertThat(processedEventCount(messageId)).isEqualTo(1);

        /*
         * Reservation failure не должен запускать payment.
         */
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldIgnoreDuplicateTicketReservedEvent() {

        Order order = createOrder(OrderState.CREATED);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_RESERVATION);

        String messageId = "result:saga:" + RESERVATION_ID + ":reserve-ticket";

        TicketReservedEvent event = new TicketReservedEvent(messageId, order.getId(), RESERVATION_ID, TICKET_ID,
                Instant.now());

        processor.process(event);

        processor.process(event);

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.PAYMENT_PENDING);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_PAYMENT);

        /*
         * Один messageId -> один processed marker.
         */
        assertThat(processedEventCount(messageId)).isEqualTo(1);

        /*
         * И главное:
         *
         * duplicate Kafka delivery не создаёт второй PaymentRequestedEvent.
         */
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldRollbackProcessedMarkerAndSagaTransitionWhenOrderCasFails() {

        /*
         * Специально создаём inconsistent state:
         *
         * Saga ещё ждёт reservation result, но Order уже CANCELED.
         *
         * Saga CAS пройдёт, Order CREATED -> TICKETS_RESERVED CAS не пройдёт.
         *
         * Вся transaction обязана откатиться.
         */
        Order order = createOrder(OrderState.CANCELED);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_RESERVATION);

        String messageId = "result:saga:" + RESERVATION_ID + ":reserve-ticket";

        TicketReservedEvent event = new TicketReservedEvent(messageId, order.getId(), RESERVATION_ID, TICKET_ID,
                Instant.now());

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to transition order");

        /*
         * Processed marker был INSERT-нут первым, но transaction откатилась.
         *
         * Kafka redelivery сможет повторить обработку.
         */
        assertThat(processedEventCount(messageId)).isZero();

        /*
         * Saga transition тоже был выполнен раньше failed Order CAS, но обязан
         * откатиться.
         */
        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_RESERVATION);

        /*
         * Order остаётся без изменений.
         */
        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CANCELED);

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldRollbackWhenReservationIdDoesNotMatchSaga() {

        Order order = createOrder(OrderState.CREATED);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_RESERVATION);

        UUID wrongReservationId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        String messageId = "result:saga:" + wrongReservationId + ":reserve-ticket";

        TicketReservedEvent event = new TicketReservedEvent(messageId, order.getId(), wrongReservationId, TICKET_ID,
                Instant.now());

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reservation result correlation mismatch");

        assertThat(processedEventCount(messageId)).isZero();

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CREATED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_RESERVATION);

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldRollbackWhenTicketIdDoesNotMatchOrder() {

        Order order = createOrder(OrderState.CREATED);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_RESERVATION);

        UUID wrongTicketId = UUID.fromString("55555555-5555-5555-5555-555555555555");

        String messageId = "result:saga:" + RESERVATION_ID + ":reserve-ticket";

        TicketReservedEvent event = new TicketReservedEvent(messageId, order.getId(), RESERVATION_ID, wrongTicketId,
                Instant.now());

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reservation result ticket mismatch");

        assertThat(processedEventCount(messageId)).isZero();

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CREATED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_RESERVATION);

        assertThat(outboxEventRepository.count()).isZero();
    }

    private Order createOrder(OrderState state) {

        Order order = new Order(null, USER_ID, EVENT_ID, TICKET_ID, PRICE, state, Instant.now());

        return orderRepository.save(order);
    }

    private OrderSaga createSaga(Long orderId, OrderSagaStatus status) {

        Instant now = Instant.now();

        int inserted = orderSagaRepository.insertIfAbsent(RESERVATION_ID, orderId, status.name(), now, now);

        assertThat(inserted).isEqualTo(1);

        return orderSagaRepository.findByOrderId(orderId).orElseThrow();
    }

    private int processedEventCount(String messageId) {

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM processed_events
                WHERE message_id = ?
                """, Integer.class, messageId);

        return count == null ? 0 : count;
    }

    private List<OutboxEvent> toList(Iterable<OutboxEvent> events) {

        return java.util.stream.StreamSupport.stream(events.spliterator(), false).toList();
    }
}