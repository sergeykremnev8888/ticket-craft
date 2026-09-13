package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("catalog_db").withUsername("postgres").withPassword("postgres");

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

    @BeforeEach
    void setUp() {

        /*
         * Важно соблюдать FK order_sagas -> orders.
         */
        jdbcTemplate.update("DELETE FROM order_sagas");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM orders");
    }

    @Test
    void shouldRollbackIdempotencyOrderAndSagaWhenOutboxCreationFails() {

        String idempotencyKey = "test-key-rollback";

        Long userId = 1L;

        UUID eventId = UUID.randomUUID();

        UUID ticketId = UUID.randomUUID();

        BigDecimal price = new BigDecimal("100.00");

        /*
         * После Step 4 external Catalog call отсутствует.
         *
         * Чтобы проверить rollback createOrder transaction, имитируем failure при
         * создании initial Saga command.
         */
        doThrow(new IllegalStateException("Simulated outbox failure")).when(outboxService)
                .saveReserveTicketCommand(any(Order.class), any(ReserveTicketCommand.class));

        assertThatThrownBy(() -> orderService.createOrder(idempotencyKey, userId, eventId, ticketId, price))
                .isInstanceOf(IllegalStateException.class).hasMessage("Simulated outbox failure");

        /*
         * Idempotency registration должна быть rollback.
         */
        assertThat(idempotencyKeyRepository.findById(idempotencyKey)).isEmpty();

        /*
         * Order INSERT был выполнен до outbox call, но должен быть rollback.
         */
        assertThat(orderRepository.count()).isZero();

        /*
         * Saga INSERT также выполняется до outbox call и должна быть rollback.
         */
        assertThat(orderSagaRepository.count()).isZero();

        verify(outboxService, times(1)).saveReserveTicketCommand(any(Order.class), any(ReserveTicketCommand.class));
    }

    @Test
    void shouldReturnSameOrderForRepeatedRequestWithSameIdempotencyKey() {

        String idempotencyKey = "test-key-repeated";

        Long userId = 1L;

        UUID eventId = UUID.randomUUID();

        UUID ticketId = UUID.randomUUID();

        BigDecimal price = new BigDecimal("100.00");

        Order firstOrder = orderService.createOrder(idempotencyKey, userId, eventId, ticketId, price);

        Order secondOrder = orderService.createOrder(idempotencyKey, userId, eventId, ticketId, price);

        assertThat(firstOrder.getId()).isNotNull();

        assertThat(secondOrder.getId()).isEqualTo(firstOrder.getId());

        /*
         * Физически создан только один Order.
         */
        assertThat(orderRepository.count()).isEqualTo(1);

        /*
         * И только одна Saga.
         */
        assertThat(orderSagaRepository.count()).isEqualTo(1);

        /*
         * Idempotency key указывает на этот Order.
         */
        assertThat(idempotencyKeyRepository.findById(idempotencyKey)).isPresent().get()
                .extracting(IdempotencyKey::getOrderId).isEqualTo(firstOrder.getId());

        /*
         * Повторный HTTP request не должен создавать ещё один ReserveTicketCommand.
         */
        verify(outboxService, times(1)).saveReserveTicketCommand(any(Order.class), any(ReserveTicketCommand.class));
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentPayload() {

        String idempotencyKey = "test-key-different-payload";

        Long userId = 1L;

        UUID eventId = UUID.randomUUID();

        UUID ticketId = UUID.randomUUID();

        BigDecimal firstPrice = new BigDecimal("100.00");

        BigDecimal secondPrice = new BigDecimal("200.00");

        Order firstOrder = orderService.createOrder(idempotencyKey, userId, eventId, ticketId, firstPrice);

        assertThat(firstOrder.getId()).isNotNull();

        assertThatThrownBy(() -> orderService.createOrder(idempotencyKey, userId, eventId, ticketId, secondPrice))
                .isInstanceOf(OrderConflictException.class);

        /*
         * Второй запрос не создаёт новый Order.
         */
        assertThat(orderRepository.count()).isEqualTo(1);

        /*
         * И не создаёт вторую Saga.
         */
        assertThat(orderSagaRepository.count()).isEqualTo(1);

        Order persistedOrder = orderRepository.findById(firstOrder.getId()).orElseThrow();

        assertThat(persistedOrder.getTotalPrice()).isEqualByComparingTo(firstPrice);

        /*
         * ReserveTicketCommand создан только для первого корректного request.
         */
        verify(outboxService, times(1)).saveReserveTicketCommand(any(Order.class), any(ReserveTicketCommand.class));
    }

    @Test
    void shouldCreateOnlyOneOrderForConcurrentRequestsWithSameIdempotencyKey() throws Exception {

        String idempotencyKey = "test-key-concurrent";

        Long userId = 1L;

        UUID eventId = UUID.randomUUID();

        UUID ticketId = UUID.randomUUID();

        BigDecimal price = new BigDecimal("100.00");

        int requestCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);

        try {

            List<Callable<Order>> tasks = new ArrayList<>();

            for (int i = 0; i < requestCount; i++) {

                tasks.add(() -> orderService.createOrder(idempotencyKey, userId, eventId, ticketId, price));
            }

            List<Future<Order>> futures = executor.invokeAll(tasks);

            List<Order> returnedOrders = new ArrayList<>();

            for (Future<Order> future : futures) {
                returnedOrders.add(future.get());
            }

            /*
             * Все запросы должны получить один logical Order.
             */
            assertThat(returnedOrders).hasSize(requestCount);

            Long createdOrderId = returnedOrders.getFirst().getId();

            assertThat(returnedOrders).extracting(Order::getId).containsOnly(createdOrderId);

            /*
             * Физически один Order.
             */
            assertThat(orderRepository.count()).isEqualTo(1);

            /*
             * И физически одна Saga.
             */
            assertThat(orderSagaRepository.count()).isEqualTo(1);

            /*
             * Idempotency key указывает на единственный Order.
             */
            assertThat(idempotencyKeyRepository.findById(idempotencyKey)).isPresent().get()
                    .extracting(IdempotencyKey::getOrderId).isEqualTo(createdOrderId);

            /*
             * Initial Saga command должна быть создана только один раз.
             */
            verify(outboxService, times(1)).saveReserveTicketCommand(any(Order.class), any(ReserveTicketCommand.class));

        } finally {

            executor.shutdownNow();
        }
    }
}