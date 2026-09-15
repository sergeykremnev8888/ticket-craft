package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.ConfirmTicketCommand;
import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import ru.ticketcraft.dto.ReleaseTicketCommand;
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
@SpringBootTest(properties = { "ticketcraft.outbox.publisher.enabled=false", "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.enabled=false" })
class PaymentResultProcessorIntegrationTest {

    private static final Long USER_ID = 10L;

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID RESERVATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID PAYMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final BigDecimal PRICE = new BigDecimal("100.00");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("order_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private PaymentResultProcessor processor;

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
    void shouldRequestTicketConfirmationWhenPaymentSucceeded() throws Exception {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_PAYMENT);

        String messageId = "payment-result-success:" + order.getId();

        PaymentSucceededEvent event = new PaymentSucceededEvent(messageId, order.getId(), PAYMENT_ID, PRICE,
                Instant.now());

        processor.process(event);

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        /*
         * Критический invariant:
         *
         * успешная оплата ещё НЕ означает CONFIRMED. Сначала catalog должен выполнить
         * RESERVED -> SOLD.
         */
        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.PAYMENT_PENDING);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_TICKET_CONFIRMATION);

        assertThat(processedEventCount(messageId)).isEqualTo(1);

        List<OutboxEvent> outboxEvents = toList(outboxEventRepository.findAll());

        assertThat(outboxEvents).hasSize(1);

        OutboxEvent outboxEvent = outboxEvents.getFirst();

        assertThat(outboxEvent.getAggregateType()).isEqualTo("ORDER");

        assertThat(outboxEvent.getAggregateId()).isEqualTo(order.getId().toString());

        assertThat(outboxEvent.getEventType()).isEqualTo("ConfirmTicket");

        assertThat(outboxEvent.getTopic()).isEqualTo("ticket-reservation-commands");

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);

        ConfirmTicketCommand command = objectMapper.readValue(outboxEvent.getPayload(), ConfirmTicketCommand.class);

        assertThat(command.messageId()).isEqualTo("saga:" + RESERVATION_ID + ":confirm-ticket");

        assertThat(command.orderId()).isEqualTo(order.getId());

        assertThat(command.reservationId()).isEqualTo(RESERVATION_ID);

        assertThat(command.ticketId()).isEqualTo(TICKET_ID);

        assertThat(command.occurredAt()).isNotNull();
    }

    @Test
    void shouldIgnoreDuplicatePaymentSucceededEvent() throws Exception {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_PAYMENT);

        String messageId = "payment-result-success:" + order.getId();

        PaymentSucceededEvent event = new PaymentSucceededEvent(messageId, order.getId(), PAYMENT_ID, PRICE,
                Instant.now());

        processor.process(event);
        processor.process(event);

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.PAYMENT_PENDING);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_TICKET_CONFIRMATION);

        assertThat(processedEventCount(messageId)).isEqualTo(1);

        List<OutboxEvent> outboxEvents = toList(outboxEventRepository.findAll());

        assertThat(outboxEvents).hasSize(1);

        assertThat(outboxEvents.getFirst().getEventType()).isEqualTo("ConfirmTicket");

        ConfirmTicketCommand command = objectMapper.readValue(outboxEvents.getFirst().getPayload(),
                ConfirmTicketCommand.class);

        assertThat(command.messageId()).isEqualTo("saga:" + RESERVATION_ID + ":confirm-ticket");
    }

    @Test
    void shouldStartReservationCompensationWhenPaymentFailed() throws Exception {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_PAYMENT);

        String messageId = "payment-result-failed:" + order.getId();

        PaymentFailedEvent event = new PaymentFailedEvent(messageId, order.getId(), PAYMENT_ID, PRICE,
                "PAYMENT_DECLINED", Instant.now());

        processor.process(event);

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.PAYMENT_FAILED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.COMPENSATING_RESERVATION);

        assertThat(processedEventCount(messageId)).isEqualTo(1);

        List<OutboxEvent> outboxEvents = toList(outboxEventRepository.findAll());

        assertThat(outboxEvents).hasSize(1);

        OutboxEvent outboxEvent = outboxEvents.getFirst();

        assertThat(outboxEvent.getEventType()).isEqualTo("ReleaseTicket");

        assertThat(outboxEvent.getTopic()).isEqualTo("ticket-reservation-commands");

        ReleaseTicketCommand command = objectMapper.readValue(outboxEvent.getPayload(), ReleaseTicketCommand.class);

        assertThat(command.messageId()).isEqualTo("saga:" + RESERVATION_ID + ":release-ticket");

        assertThat(command.orderId()).isEqualTo(order.getId());

        assertThat(command.reservationId()).isEqualTo(RESERVATION_ID);

        assertThat(command.ticketId()).isEqualTo(TICKET_ID);
    }

    @Test
    void shouldIgnoreDuplicatePaymentFailedEvent() throws Exception {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_PAYMENT);

        String messageId = "payment-result-failed:" + order.getId();

        PaymentFailedEvent event = new PaymentFailedEvent(messageId, order.getId(), PAYMENT_ID, PRICE,
                "PAYMENT_DECLINED", Instant.now());

        processor.process(event);
        processor.process(event);

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderState.PAYMENT_FAILED);

        assertThat(orderSagaRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderSagaStatus.COMPENSATING_RESERVATION);

        assertThat(processedEventCount(messageId)).isEqualTo(1);

        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldRollbackWhenPaymentAmountDoesNotMatchOrder() {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_PAYMENT);

        String messageId = "payment-result-invalid-amount:" + order.getId();

        PaymentSucceededEvent event = new PaymentSucceededEvent(messageId, order.getId(), PAYMENT_ID,
                new BigDecimal("999.00"), Instant.now());

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Payment amount mismatch");

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderState.PAYMENT_PENDING);

        assertThat(orderSagaRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderSagaStatus.WAITING_FOR_PAYMENT);

        assertThat(processedEventCount(messageId)).isZero();

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldRollbackWhenSagaTransitionFails() {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        /*
         * Некорректное состояние: success payment пришёл, но saga уже не
         * WAITING_FOR_PAYMENT.
         */
        createSaga(order.getId(), OrderSagaStatus.COMPLETED);

        String messageId = "payment-result-invalid-saga:" + order.getId();

        PaymentSucceededEvent event = new PaymentSucceededEvent(messageId, order.getId(), PAYMENT_ID, PRICE,
                Instant.now());

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to transition saga");

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderState.PAYMENT_PENDING);

        assertThat(orderSagaRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderSagaStatus.COMPLETED);

        assertThat(processedEventCount(messageId)).isZero();

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

        return StreamSupport.stream(events.spliterator(), false).toList();
    }
}