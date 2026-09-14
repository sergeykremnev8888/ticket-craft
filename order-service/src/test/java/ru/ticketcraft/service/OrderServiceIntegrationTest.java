package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.exception.OrderConflictException;
import ru.ticketcraft.model.IdempotencyKey;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.IdempotencyKeyRepository;
import ru.ticketcraft.repository.OrderRepository;
import ru.ticketcraft.repository.OrderSagaRepository;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:9092",
        "ticketcraft.outbox.publisher.enabled=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.enabled=false"
})
@Testcontainers
@ActiveProfiles("test")
class OrderServiceIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("order_db")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderSagaRepository orderSagaRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private OutboxService outboxService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        /*
         * Важно соблюдать FK:
         *
         * order_sagas -> orders
         * idempotency_keys -> orders
         */
        jdbcTemplate.update("DELETE FROM order_sagas");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM orders");
    }

    @Test
    void shouldRollbackIdempotencyOrderAndSagaWhenOutboxCreationFails() {
        String idempotencyKey = "test-key-rollback";

        Long userId = 1L;

        UUID ticketId = UUID.randomUUID();

        /*
         * Order, Saga и idempotency registration создаются в одной transaction.
         *
         * Эмулируем failure на последнем шаге — создании initial
         * ReserveTicketCommand в transactional outbox.
         */
        doThrow(new IllegalStateException("Simulated outbox failure"))
                .when(outboxService)
                .saveReserveTicketCommand(
                        any(Order.class),
                        any(ReserveTicketCommand.class)
                );

        assertThatThrownBy(() ->
                orderService.createOrder(
                        idempotencyKey,
                        userId,
                        ticketId
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated outbox failure");

        /*
         * Idempotency registration должна откатиться.
         */
        assertThat(
                idempotencyKeyRepository.findById(idempotencyKey)
        ).isEmpty();

        /*
         * Order INSERT выполняется до outbox call,
         * но transaction должна откатить его.
         */
        assertThat(orderRepository.count())
                .isZero();

        /*
         * Saga также создаётся до outbox call
         * и должна быть rollback.
         */
        assertThat(orderSagaRepository.count())
                .isZero();

        verify(outboxService, times(1))
                .saveReserveTicketCommand(
                        any(Order.class),
                        any(ReserveTicketCommand.class)
                );
    }

    @Test
    void shouldCreateOrderWithPendingAuthoritativeCatalogDetails() {
        String idempotencyKey = "test-key-create";

        Long userId = 1L;

        UUID ticketId = UUID.randomUUID();

        Order order = orderService.createOrder(
                idempotencyKey,
                userId,
                ticketId
        );

        assertThat(order.getId())
                .isNotNull();

        assertThat(order.getUserId())
                .isEqualTo(userId);

        assertThat(order.getTicketId())
                .isEqualTo(ticketId);

        /*
         * eventId и price больше не принимаются от клиента.
         *
         * Authoritative значения придут позже из catalog-service
         * через TicketReservedEvent.
         */
        assertThat(order.getEventId())
                .isNull();

        assertThat(order.getTotalPrice())
                .isNull();

        assertThat(order.getStatus())
                .isEqualTo(OrderState.CREATED);

        assertThat(order.getCreatedAt())
                .isNotNull();

        assertThat(orderRepository.count())
                .isEqualTo(1);

        assertThat(orderSagaRepository.count())
                .isEqualTo(1);

        assertThat(
                idempotencyKeyRepository.findById(idempotencyKey)
        )
                .isPresent()
                .get()
                .extracting(IdempotencyKey::getOrderId)
                .isEqualTo(order.getId());

        verify(outboxService, times(1))
                .saveReserveTicketCommand(
                        any(Order.class),
                        any(ReserveTicketCommand.class)
                );
    }

    @Test
    void shouldReturnSameOrderForRepeatedRequestWithSameIdempotencyKey() {
        String idempotencyKey = "test-key-repeated";

        Long userId = 1L;

        UUID ticketId = UUID.randomUUID();

        Order firstOrder = orderService.createOrder(
                idempotencyKey,
                userId,
                ticketId
        );

        Order secondOrder = orderService.createOrder(
                idempotencyKey,
                userId,
                ticketId
        );

        assertThat(firstOrder.getId())
                .isNotNull();

        assertThat(secondOrder.getId())
                .isEqualTo(firstOrder.getId());

        assertThat(secondOrder.getTicketId())
                .isEqualTo(ticketId);

        /*
         * До получения TicketReservedEvent authoritative catalog details
         * ещё отсутствуют.
         */
        assertThat(secondOrder.getEventId())
                .isNull();

        assertThat(secondOrder.getTotalPrice())
                .isNull();

        /*
         * Физически должен существовать только один Order.
         */
        assertThat(orderRepository.count())
                .isEqualTo(1);

        /*
         * И только одна Saga.
         */
        assertThat(orderSagaRepository.count())
                .isEqualTo(1);

        /*
         * Idempotency key должен указывать на этот Order.
         */
        assertThat(
                idempotencyKeyRepository.findById(idempotencyKey)
        )
                .isPresent()
                .get()
                .extracting(IdempotencyKey::getOrderId)
                .isEqualTo(firstOrder.getId());

        /*
         * Повторный HTTP request не должен создавать
         * второй ReserveTicketCommand.
         */
        verify(outboxService, times(1))
                .saveReserveTicketCommand(
                        any(Order.class),
                        any(ReserveTicketCommand.class)
                );
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentTicket() {
        String idempotencyKey = "test-key-different-payload";

        Long userId = 1L;

        UUID firstTicketId = UUID.randomUUID();

        UUID secondTicketId = UUID.randomUUID();

        Order firstOrder = orderService.createOrder(
                idempotencyKey,
                userId,
                firstTicketId
        );

        assertThat(firstOrder.getId())
                .isNotNull();

        /*
         * Canonical request теперь состоит из:
         *
         * userId + ticketId
         *
         * Поэтому тот же Idempotency-Key с другим ticketId
         * является другим payload и должен завершиться conflict.
         */
        assertThatThrownBy(() ->
                orderService.createOrder(
                        idempotencyKey,
                        userId,
                        secondTicketId
                ))
                .isInstanceOf(OrderConflictException.class);

        /*
         * Второй request не создаёт новый Order.
         */
        assertThat(orderRepository.count())
                .isEqualTo(1);

        /*
         * И не создаёт вторую Saga.
         */
        assertThat(orderSagaRepository.count())
                .isEqualTo(1);

        Order persistedOrder =
                orderRepository.findById(firstOrder.getId())
                        .orElseThrow();

        assertThat(persistedOrder.getTicketId())
                .isEqualTo(firstTicketId);

        assertThat(persistedOrder.getEventId())
                .isNull();

        assertThat(persistedOrder.getTotalPrice())
                .isNull();

        /*
         * ReserveTicketCommand создаётся только
         * для первого корректного request.
         */
        verify(outboxService, times(1))
                .saveReserveTicketCommand(
                        any(Order.class),
                        any(ReserveTicketCommand.class)
                );
    }

    @Test
    void shouldCreateOnlyOneOrderForConcurrentRequestsWithSameIdempotencyKey()
            throws Exception {

        String idempotencyKey = "test-key-concurrent";

        Long userId = 1L;

        UUID ticketId = UUID.randomUUID();

        int requestCount = 20;

        ExecutorService executor =
                Executors.newFixedThreadPool(requestCount);

        try {
            List<Callable<Order>> tasks =
                    new ArrayList<>();

            for (int i = 0; i < requestCount; i++) {
                tasks.add(() ->
                        orderService.createOrder(
                                idempotencyKey,
                                userId,
                                ticketId
                        )
                );
            }

            List<Future<Order>> futures =
                    executor.invokeAll(tasks);

            List<Order> returnedOrders =
                    new ArrayList<>();

            for (Future<Order> future : futures) {
                returnedOrders.add(future.get());
            }

            /*
             * Все concurrent requests должны получить
             * один logical Order.
             */
            assertThat(returnedOrders)
                    .hasSize(requestCount);

            Long createdOrderId =
                    returnedOrders.getFirst().getId();

            assertThat(createdOrderId)
                    .isNotNull();

            assertThat(returnedOrders)
                    .extracting(Order::getId)
                    .containsOnly(createdOrderId);

            assertThat(returnedOrders)
                    .extracting(Order::getTicketId)
                    .containsOnly(ticketId);

            /*
             * Authoritative catalog details ещё не получены.
             */
            assertThat(returnedOrders)
                    .extracting(Order::getEventId)
                    .containsOnlyNulls();

            assertThat(returnedOrders)
                    .extracting(Order::getTotalPrice)
                    .containsOnlyNulls();

            /*
             * Физически должен существовать один Order.
             */
            assertThat(orderRepository.count())
                    .isEqualTo(1);

            /*
             * И физически одна Saga.
             */
            assertThat(orderSagaRepository.count())
                    .isEqualTo(1);

            /*
             * Idempotency key указывает на единственный Order.
             */
            assertThat(
                    idempotencyKeyRepository.findById(idempotencyKey)
            )
                    .isPresent()
                    .get()
                    .extracting(IdempotencyKey::getOrderId)
                    .isEqualTo(createdOrderId);

            /*
             * Initial ReserveTicketCommand должен быть создан
             * только один раз.
             */
            verify(outboxService, times(1))
                    .saveReserveTicketCommand(
                            any(Order.class),
                            any(ReserveTicketCommand.class)
                    );

        } finally {
            executor.shutdownNow();
        }
    }
}
