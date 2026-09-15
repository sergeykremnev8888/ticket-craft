package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import ru.ticketcraft.dto.PaymentRefundedEvent;
import ru.ticketcraft.dto.RefundPaymentCommand;
import ru.ticketcraft.dto.TicketConfirmationFailedEvent;
import ru.ticketcraft.dto.TicketConfirmationFailureReason;
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
class PaymentCompensationIntegrationTest {

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
    private TicketReservationResultProcessor ticketReservationResultProcessor;

    @Autowired
    private PaymentResultProcessor paymentResultProcessor;

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
    void shouldStartPaymentCompensationWhenTicketConfirmationFails() throws Exception {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSagaWaitingForTicketConfirmation(order.getId());

        String messageId = "result:saga:" + RESERVATION_ID + ":confirm-ticket";

        TicketConfirmationFailedEvent event = new TicketConfirmationFailedEvent(messageId, order.getId(),
                RESERVATION_ID, TICKET_ID, TicketConfirmationFailureReason.RESERVATION_EXPIRED, Instant.now());

        ticketReservationResultProcessor.process(event);

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        /*
         * Деньги уже были списаны.
         *
         * Пока refund не подтверждён, Order не должен становиться CANCELED.
         */
        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.PAYMENT_PENDING);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.COMPENSATING_PAYMENT);

        assertThat(persistedSaga.getPaymentId()).isEqualTo(PAYMENT_ID);

        assertThat(processedEventCount(messageId)).isEqualTo(1);

        List<OutboxEvent> outboxEvents = toList(outboxEventRepository.findAll());

        assertThat(outboxEvents).hasSize(1);

        OutboxEvent outboxEvent = outboxEvents.getFirst();

        assertThat(outboxEvent.getAggregateType()).isEqualTo("ORDER");

        assertThat(outboxEvent.getAggregateId()).isEqualTo(order.getId().toString());

        assertThat(outboxEvent.getEventType()).isEqualTo("RefundPayment");

        assertThat(outboxEvent.getTopic()).isEqualTo("payment-commands");

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);

        RefundPaymentCommand command = objectMapper.readValue(outboxEvent.getPayload(), RefundPaymentCommand.class);

        assertThat(command.messageId()).isEqualTo("saga:" + RESERVATION_ID + ":refund-payment");

        assertThat(command.orderId()).isEqualTo(order.getId());

        assertThat(command.paymentId()).isEqualTo(PAYMENT_ID);

        assertThat(command.amount()).isEqualByComparingTo(PRICE);

        assertThat(command.reason()).isEqualTo(TicketConfirmationFailureReason.RESERVATION_EXPIRED);

        assertThat(command.occurredAt()).isNotNull();
    }

    @Test
    void shouldIgnoreDuplicateTicketConfirmationFailedEvent() throws Exception {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSagaWaitingForTicketConfirmation(order.getId());

        String messageId = "result:saga:" + RESERVATION_ID + ":confirm-ticket";

        TicketConfirmationFailedEvent event = new TicketConfirmationFailedEvent(messageId, order.getId(),
                RESERVATION_ID, TICKET_ID, TicketConfirmationFailureReason.RESERVATION_EXPIRED, Instant.now());

        ticketReservationResultProcessor.process(event);
        ticketReservationResultProcessor.process(event);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.COMPENSATING_PAYMENT);

        assertThat(processedEventCount(messageId)).isEqualTo(1);

        /*
         * Duplicate Kafka delivery не должен создавать второй RefundPaymentCommand.
         */
        assertThat(outboxEventRepository.count()).isEqualTo(1);

        OutboxEvent outboxEvent = toList(outboxEventRepository.findAll()).getFirst();

        RefundPaymentCommand command = objectMapper.readValue(outboxEvent.getPayload(), RefundPaymentCommand.class);

        assertThat(command.paymentId()).isEqualTo(PAYMENT_ID);
    }

    @Test
    void shouldCompleteCompensationWhenPaymentRefunded() throws Exception {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSagaWaitingForTicketConfirmation(order.getId());

        /*
         * Первая половина compensation:
         *
         * TicketConfirmationFailed -> COMPENSATING_PAYMENT -> RefundPaymentCommand
         */
        String confirmationFailureMessageId = "result:saga:" + RESERVATION_ID + ":confirm-ticket";

        TicketConfirmationFailedEvent confirmationFailedEvent = new TicketConfirmationFailedEvent(
                confirmationFailureMessageId, order.getId(), RESERVATION_ID, TICKET_ID,
                TicketConfirmationFailureReason.RESERVATION_EXPIRED, Instant.now());

        ticketReservationResultProcessor.process(confirmationFailedEvent);

        OrderSaga compensatingSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(compensatingSaga.getStatus()).isEqualTo(OrderSagaStatus.COMPENSATING_PAYMENT);

        /*
         * Имитируем результат payment-service.
         */
        String refundedMessageId = "payment:" + PAYMENT_ID + ":refunded";

        PaymentRefundedEvent refundedEvent = new PaymentRefundedEvent(refundedMessageId, order.getId(), PAYMENT_ID,
                PRICE, Instant.now());

        paymentResultProcessor.process(refundedEvent);

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CANCELED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.FAILED);

        assertThat(persistedSaga.getPaymentId()).isEqualTo(PAYMENT_ID);

        /*
         * Оба входящих события обработаны ровно один раз.
         */
        assertThat(processedEventCount(confirmationFailureMessageId)).isEqualTo(1);

        assertThat(processedEventCount(refundedMessageId)).isEqualTo(1);

        /*
         * В outbox остаётся только RefundPaymentCommand.
         *
         * PaymentRefundedEvent является terminal result и не должен создавать новый
         * domain command.
         */
        List<OutboxEvent> outboxEvents = toList(outboxEventRepository.findAll());

        assertThat(outboxEvents).hasSize(1);

        assertThat(outboxEvents.getFirst().getEventType()).isEqualTo("RefundPayment");
    }

    @Test
    void shouldIgnoreDuplicatePaymentRefundedEvent() {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSagaCompensatingPayment(order.getId());

        String messageId = "payment:" + PAYMENT_ID + ":refunded";

        PaymentRefundedEvent event = new PaymentRefundedEvent(messageId, order.getId(), PAYMENT_ID, PRICE,
                Instant.now());

        paymentResultProcessor.process(event);
        paymentResultProcessor.process(event);

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CANCELED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.FAILED);

        assertThat(processedEventCount(messageId)).isEqualTo(1);

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldRollbackRefundResultWhenPaymentIdDoesNotMatchSaga() {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSagaCompensatingPayment(order.getId());

        UUID wrongPaymentId = UUID.fromString("55555555-5555-5555-5555-555555555555");

        String messageId = "payment:" + wrongPaymentId + ":refunded";

        PaymentRefundedEvent event = new PaymentRefundedEvent(messageId, order.getId(), wrongPaymentId, PRICE,
                Instant.now());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> paymentResultProcessor.process(event))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Refunded payment id mismatch");

        /*
         * Вся transaction должна откатиться, включая processed_events marker.
         */
        assertThat(processedEventCount(messageId)).isZero();

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.PAYMENT_PENDING);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.COMPENSATING_PAYMENT);
    }

    private Order createOrder(OrderState state) {

        Order order = new Order(null, USER_ID, EVENT_ID, TICKET_ID, PRICE, state, Instant.now());

        return orderRepository.save(order);
    }

    private OrderSaga createSagaWaitingForTicketConfirmation(Long orderId) {

        Instant now = Instant.now();

        int inserted = orderSagaRepository.insertIfAbsent(RESERVATION_ID, orderId,
                OrderSagaStatus.WAITING_FOR_PAYMENT.name(), now, now);

        assertThat(inserted).isEqualTo(1);

        int transitioned = orderSagaRepository.transitionAndSetPaymentId(orderId,
                OrderSagaStatus.WAITING_FOR_PAYMENT.name(), OrderSagaStatus.WAITING_FOR_TICKET_CONFIRMATION.name(),
                PAYMENT_ID, Instant.now());

        assertThat(transitioned).isEqualTo(1);

        return orderSagaRepository.findByOrderId(orderId).orElseThrow();
    }

    private OrderSaga createSagaCompensatingPayment(Long orderId) {

        createSagaWaitingForTicketConfirmation(orderId);

        int transitioned = orderSagaRepository.transition(orderId,
                OrderSagaStatus.WAITING_FOR_TICKET_CONFIRMATION.name(), OrderSagaStatus.COMPENSATING_PAYMENT.name(),
                Instant.now());

        assertThat(transitioned).isEqualTo(1);

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