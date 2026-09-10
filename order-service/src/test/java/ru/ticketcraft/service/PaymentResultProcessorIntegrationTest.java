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
@SpringBootTest(properties = { "ticketcraft.outbox.publisher.enabled=false" })
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
    void shouldConfirmOrderAndCompleteSagaWhenPaymentSucceeded() {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_PAYMENT);

        String messageId = "payment-result-success:" + order.getId();

        PaymentSucceededEvent event = new PaymentSucceededEvent(messageId, order.getId(), PAYMENT_ID, PRICE,
                Instant.now());

        processor.process(event);

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CONFIRMED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.COMPLETED);

        assertThat(processedEventCount(messageId)).isEqualTo(1);

        assertThat(toList(outboxEventRepository.findAll())).isEmpty();
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

        OutboxEvent outboxEvent = outboxEvents.get(0);

        assertThat(outboxEvent.getAggregateType()).isEqualTo("ORDER");

        assertThat(outboxEvent.getAggregateId()).isEqualTo(order.getId().toString());

        assertThat(outboxEvent.getEventType()).isEqualTo("ReleaseTicket");

        assertThat(outboxEvent.getTopic()).isEqualTo("ticket-reservation-commands");

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);

        ReleaseTicketCommand command = objectMapper.readValue(outboxEvent.getPayload(), ReleaseTicketCommand.class);

        assertThat(command.orderId()).isEqualTo(order.getId());

        assertThat(command.reservationId()).isEqualTo(RESERVATION_ID);

        assertThat(command.ticketId()).isEqualTo(TICKET_ID);

        assertThat(command.messageId()).isEqualTo("saga:" + RESERVATION_ID + ":release-ticket");

        assertThat(command.occurredAt()).isNotNull();
    }

    @Test
    void shouldIgnoreDuplicatePaymentSucceededEvent() {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_PAYMENT);

        String messageId = "payment-result-success:" + order.getId();

        PaymentSucceededEvent event = new PaymentSucceededEvent(messageId, order.getId(), PAYMENT_ID, PRICE,
                Instant.now());

        processor.process(event);

        processor.process(event);

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CONFIRMED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.COMPLETED);

        assertThat(processedEventCount(messageId)).isEqualTo(1);

        assertThat(toList(outboxEventRepository.findAll())).isEmpty();
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

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.PAYMENT_FAILED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.COMPENSATING_RESERVATION);

        assertThat(processedEventCount(messageId)).isEqualTo(1);

        List<OutboxEvent> outboxEvents = toList(outboxEventRepository.findAll());

        assertThat(outboxEvents).hasSize(1);

        ReleaseTicketCommand command = objectMapper.readValue(outboxEvents.get(0).getPayload(),
                ReleaseTicketCommand.class);

        assertThat(command.messageId()).isEqualTo("saga:" + RESERVATION_ID + ":release-ticket");
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

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.PAYMENT_PENDING);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_PAYMENT);

        /*
         * Критический invariant:
         *
         * processed marker был вставлен в начале process(), но транзакция должна
         * полностью откатить его после ошибки.
         */
        assertThat(processedEventCount(messageId)).isZero();

        assertThat(toList(outboxEventRepository.findAll())).isEmpty();
    }

    @Test
    void shouldRollbackSagaTransitionWhenOrderTransitionFails() {

        /*
         * Намеренно создаём неконсистентное состояние:
         *
         * Saga ожидает payment result, но Order уже CONFIRMED.
         *
         * Saga CAS сначала успешно изменится на COMPLETED, затем Order CAS
         *
         * PAYMENT_PENDING -> CONFIRMED
         *
         * должен вернуть false.
         *
         * Вся транзакция должна откатиться.
         */
        Order order = createOrder(OrderState.CONFIRMED);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_PAYMENT);

        String messageId = "payment-result-order-cas-failure:" + order.getId();

        PaymentSucceededEvent event = new PaymentSucceededEvent(messageId, order.getId(), PAYMENT_ID, PRICE,
                Instant.now());

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to transition order");

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CONFIRMED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        /*
         * transitionSaga() выполнялся раньше transitionOrder(), поэтому этим assert мы
         * реально проверяем rollback предыдущего UPDATE.
         */
        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_PAYMENT);

        /*
         * processed_events также должен откатиться.
         */
        assertThat(processedEventCount(messageId)).isZero();

        assertThat(toList(outboxEventRepository.findAll())).isEmpty();
    }

    private Order createOrder(OrderState state) {

        Order order = new Order(null, USER_ID, EVENT_ID, TICKET_ID, PRICE, state, Instant.now());

        return orderRepository.save(order);
    }

    private void createSaga(Long orderId, OrderSagaStatus status) {

        Instant now = Instant.now();

        int inserted = orderSagaRepository.insertIfAbsent(RESERVATION_ID, orderId, status.name(), now, now);

        assertThat(inserted).isEqualTo(1);
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