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

import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.dto.TicketConfirmedEvent;
import ru.ticketcraft.dto.TicketReleasedEvent;
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
@SpringBootTest(properties = { "ticketcraft.outbox.publisher.enabled=false", "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.enabled=false" })
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
                EVENT_ID, PRICE, Instant.now());

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
    void shouldUseAuthoritativeCatalogPriceAndEventForPayment() throws Exception {

        Order order = createOrder(OrderState.CREATED);
        order.setEventId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        order.setTotalPrice(new BigDecimal("1.00"));
        orderRepository.save(order);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_RESERVATION);

        String messageId = "result:saga:" + RESERVATION_ID + ":reserve-ticket";

        TicketReservedEvent event = new TicketReservedEvent(messageId, order.getId(), RESERVATION_ID, TICKET_ID,
                EVENT_ID, PRICE, Instant.now());

        processor.process(event);

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(persistedOrder.getEventId()).isEqualTo(EVENT_ID);
        assertThat(persistedOrder.getTotalPrice()).isEqualByComparingTo(PRICE);

        OutboxEvent outboxEvent = toList(outboxEventRepository.findAll()).getFirst();
        PaymentRequestedEvent paymentRequestedEvent = objectMapper.readValue(outboxEvent.getPayload(),
                PaymentRequestedEvent.class);

        assertThat(paymentRequestedEvent.amount()).isEqualByComparingTo(PRICE);
    }

    @Test
    void shouldIgnoreDuplicateTicketReservedEvent() {

        Order order = createOrder(OrderState.CREATED);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_RESERVATION);

        String messageId = "result:saga:" + RESERVATION_ID + ":reserve-ticket";

        TicketReservedEvent event = new TicketReservedEvent(messageId, order.getId(), RESERVATION_ID, TICKET_ID,
                EVENT_ID, PRICE, Instant.now());

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
                EVENT_ID, PRICE, Instant.now());

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
                EVENT_ID, PRICE, Instant.now());

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
                EVENT_ID, PRICE, Instant.now());

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reservation result ticket mismatch");

        assertThat(processedEventCount(messageId)).isZero();

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CREATED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_RESERVATION);

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldCancelOrderAndFailSagaWhenTicketReleased() {

        // Given
        Order order = createOrder(OrderState.PAYMENT_FAILED);

        createSaga(order.getId(), OrderSagaStatus.COMPENSATING_RESERVATION);

        String messageId = "result:saga:" + RESERVATION_ID + ":release-ticket";

        TicketReleasedEvent event = new TicketReleasedEvent(messageId, order.getId(), RESERVATION_ID, TICKET_ID,
                Instant.now());

        // When
        processor.process(event);

        // Then
        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CANCELED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.FAILED);

        assertThat(processedEventCount(messageId)).isEqualTo(1);
    }

    @Test
    void shouldIgnoreDuplicateTicketReleasedEvent() {

        // Given
        Order order = createOrder(OrderState.PAYMENT_FAILED);

        createSaga(order.getId(), OrderSagaStatus.COMPENSATING_RESERVATION);

        String messageId = "result:saga:" + RESERVATION_ID + ":release-ticket";

        TicketReleasedEvent event = new TicketReleasedEvent(messageId, order.getId(), RESERVATION_ID, TICKET_ID,
                Instant.now());

        // When
        processor.process(event);

        processor.process(event);

        // Then
        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CANCELED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.FAILED);

        /*
         * Второй delivery должен остановиться на persistent idempotency marker.
         */
        assertThat(processedEventCount(messageId)).isEqualTo(1);
    }

    @Test
    void shouldRollbackSagaAndProcessedMarkerWhenCancelOrderFails() {

        // Given

        /*
         * Намеренно неконсистентное состояние.
         *
         * Saga готова к завершению compensation, но Order не находится в
         * PAYMENT_FAILED.
         */
        Order order = createOrder(OrderState.CONFIRMED);

        createSaga(order.getId(), OrderSagaStatus.COMPENSATING_RESERVATION);

        String messageId = "result:saga:" + RESERVATION_ID + ":release-ticket";

        TicketReleasedEvent event = new TicketReleasedEvent(messageId, order.getId(), RESERVATION_ID, TICKET_ID,
                Instant.now());

        // When / Then
        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to transition order");

        /*
         * Order не должен измениться.
         */
        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CONFIRMED);

        /*
         * transitionSaga() выполнялся раньше, поэтому если @Transactional работает
         * правильно, Saga UPDATE тоже обязан откатиться.
         */
        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.COMPENSATING_RESERVATION);

        /*
         * Persistent idempotency marker также обязан быть rollback-нут.
         *
         * Иначе Kafka redelivery уже никогда не сможет повторить событие.
         */
        assertThat(processedEventCount(messageId)).isZero();
    }

    @Test
    void shouldRollbackWhenTicketReleasedReservationIdDoesNotMatchSaga() {

        // Given
        Order order = createOrder(OrderState.PAYMENT_FAILED);

        createSaga(order.getId(), OrderSagaStatus.COMPENSATING_RESERVATION);

        UUID wrongReservationId = UUID.randomUUID();

        String messageId = "result:saga:" + wrongReservationId + ":release-ticket";

        TicketReleasedEvent event = new TicketReleasedEvent(messageId, order.getId(), wrongReservationId, TICKET_ID,
                Instant.now());

        // When / Then
        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reservation result correlation mismatch")
                .hasMessageContaining(RESERVATION_ID.toString()).hasMessageContaining(wrongReservationId.toString());

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.PAYMENT_FAILED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.COMPENSATING_RESERVATION);

        assertThat(processedEventCount(messageId)).isZero();
    }

    @Test
    void shouldRollbackWhenTicketReleasedTicketIdDoesNotMatchOrder() {

        // Given
        Order order = createOrder(OrderState.PAYMENT_FAILED);

        createSaga(order.getId(), OrderSagaStatus.COMPENSATING_RESERVATION);

        UUID wrongTicketId = UUID.randomUUID();

        String messageId = "result:saga:" + RESERVATION_ID + ":release-ticket";

        TicketReleasedEvent event = new TicketReleasedEvent(messageId, order.getId(), RESERVATION_ID, wrongTicketId,
                Instant.now());

        // When / Then
        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reservation result ticket mismatch").hasMessageContaining(TICKET_ID.toString())
                .hasMessageContaining(wrongTicketId.toString());

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.PAYMENT_FAILED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.COMPENSATING_RESERVATION);

        assertThat(processedEventCount(messageId)).isZero();
    }

    @Test
    void shouldConfirmOrderAndCompleteSagaWhenTicketConfirmed() throws Exception {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_TICKET_CONFIRMATION);

        String messageId = "result:saga:" + RESERVATION_ID + ":confirm-ticket";

        TicketConfirmedEvent event = new TicketConfirmedEvent(messageId, order.getId(), RESERVATION_ID, TICKET_ID,
                Instant.now());

        processor.process(event);

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderState.CONFIRMED);

        OrderSaga persistedSaga = orderSagaRepository.findByOrderId(order.getId()).orElseThrow();

        assertThat(persistedSaga.getStatus()).isEqualTo(OrderSagaStatus.COMPLETED);

        assertThat(processedEventCount(messageId)).isEqualTo(1);

        List<OutboxEvent> outboxEvents = toList(outboxEventRepository.findAll());

        assertThat(outboxEvents).hasSize(1);

        OutboxEvent outboxEvent = outboxEvents.getFirst();

        assertThat(outboxEvent.getAggregateType()).isEqualTo("ORDER");

        assertThat(outboxEvent.getAggregateId()).isEqualTo(order.getId().toString());

        assertThat(outboxEvent.getEventType()).isEqualTo("OrderConfirmed");

        assertThat(outboxEvent.getTopic()).isEqualTo("order-events");

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);

        OrderEvent orderConfirmedEvent = objectMapper.readValue(outboxEvent.getPayload(), OrderEvent.class);

        assertThat(orderConfirmedEvent.getMessageId()).isEqualTo("saga:" + RESERVATION_ID + ":order-confirmed");

        assertThat(orderConfirmedEvent.getOrderId()).isEqualTo(order.getId());

        assertThat(orderConfirmedEvent.getUserId()).isEqualTo(USER_ID);

        assertThat(orderConfirmedEvent.getEventId()).isEqualTo(EVENT_ID);

        assertThat(orderConfirmedEvent.getTicketIds()).containsExactly(TICKET_ID);

        assertThat(orderConfirmedEvent.getTotalPrice()).isEqualByComparingTo(PRICE);

        assertThat(orderConfirmedEvent.getState()).isEqualTo(OrderState.CONFIRMED);
    }

    @Test
    void shouldIgnoreDuplicateTicketConfirmedEvent() {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_TICKET_CONFIRMATION);

        String messageId = "result:saga:" + RESERVATION_ID + ":confirm-ticket";

        TicketConfirmedEvent event = new TicketConfirmedEvent(messageId, order.getId(), RESERVATION_ID, TICKET_ID,
                Instant.now());

        processor.process(event);
        processor.process(event);

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderState.CONFIRMED);

        assertThat(orderSagaRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderSagaStatus.COMPLETED);

        assertThat(processedEventCount(messageId)).isEqualTo(1);

        /*
         * Duplicate TicketConfirmed не должен породить второй OrderConfirmed.
         */
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldRollbackTicketConfirmedWhenReservationIdDoesNotMatchSaga() {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_TICKET_CONFIRMATION);

        UUID wrongReservationId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        String messageId = "result:saga:" + wrongReservationId + ":confirm-ticket";

        TicketConfirmedEvent event = new TicketConfirmedEvent(messageId, order.getId(), wrongReservationId, TICKET_ID,
                Instant.now());

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reservation result correlation mismatch");

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderState.PAYMENT_PENDING);

        assertThat(orderSagaRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderSagaStatus.WAITING_FOR_TICKET_CONFIRMATION);

        /*
         * processed marker должен rollback-нуться.
         */
        assertThat(processedEventCount(messageId)).isZero();

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldRollbackTicketConfirmedWhenTicketIdDoesNotMatchOrder() {

        Order order = createOrder(OrderState.PAYMENT_PENDING);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_TICKET_CONFIRMATION);

        UUID wrongTicketId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        String messageId = "result:saga:" + RESERVATION_ID + ":confirm-ticket";

        TicketConfirmedEvent event = new TicketConfirmedEvent(messageId, order.getId(), RESERVATION_ID, wrongTicketId,
                Instant.now());

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reservation result ticket mismatch");

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderState.PAYMENT_PENDING);

        assertThat(orderSagaRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderSagaStatus.WAITING_FOR_TICKET_CONFIRMATION);

        assertThat(processedEventCount(messageId)).isZero();

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldRollbackTicketConfirmedWhenOrderTransitionFails() {

        /*
         * Намеренно inconsistent state:
         *
         * catalog подтвердил SOLD, saga ждёт confirmation, но Order почему-то уже
         * CONFIRMED.
         */
        Order order = createOrder(OrderState.CONFIRMED);

        createSaga(order.getId(), OrderSagaStatus.WAITING_FOR_TICKET_CONFIRMATION);

        String messageId = "result:saga:" + RESERVATION_ID + ":confirm-ticket";

        TicketConfirmedEvent event = new TicketConfirmedEvent(messageId, order.getId(), RESERVATION_ID, TICKET_ID,
                Instant.now());

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to transition order");

        /*
         * Saga CAS выполнялся раньше Order CAS. Вся транзакция обязана откатиться.
         */
        assertThat(orderSagaRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderSagaStatus.WAITING_FOR_TICKET_CONFIRMATION);

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderState.CONFIRMED);

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

        return java.util.stream.StreamSupport.stream(events.spliterator(), false).toList();
    }
}