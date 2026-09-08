package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.client.CatalogClient;
import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.exception.OrderConflictException;
import ru.ticketcraft.model.IdempotencyKey;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.IdempotencyKeyRepository;
import ru.ticketcraft.repository.OrderRepository;

@SpringBootTest(properties = { "spring.kafka.bootstrap-servers=localhost:9092" })
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
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CatalogClient catalogClient;

    @MockitoBean
    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        Mockito.reset(catalogClient);

        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM orders");
    }

    @Test
    void shouldRollbackIdempotencyKeyWhenOrderCreationFails() {
        // given
        String idempotencyKey = "test-key-rollback";
        Long userId = 1L;
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("100.00");

        when(catalogClient.reserveTicket(ticketId)).thenThrow(new RuntimeException("Catalog service unavailable"));

        // when / then
        assertThatThrownBy(() -> orderService.createOrder(idempotencyKey, userId, eventId, ticketId, price))
                .isInstanceOf(RuntimeException.class).hasMessage("Catalog service unavailable");

        // then: idempotency record must be rolled back
        assertThat(idempotencyKeyRepository.findById(idempotencyKey)).isEmpty();

        // then: order must not be persisted
        assertThat(orderRepository.count()).isZero();

        verify(catalogClient).reserveTicket(ticketId);
    }

    @Test
    void shouldReturnSameOrderForRepeatedRequestWithSameIdempotencyKey() {
        String idempotencyKey = "test-key-repeated";
        Long userId = 1L;
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("100.00");

        when(catalogClient.reserveTicket(ticketId)).thenReturn(true);

        Order firstOrder = orderService.createOrder(idempotencyKey, userId, eventId, ticketId, price);

        Order secondOrder = orderService.createOrder(idempotencyKey, userId, eventId, ticketId, price);

        assertThat(firstOrder.getId()).isNotNull();

        assertThat(secondOrder.getId()).isEqualTo(firstOrder.getId());

        assertThat(orderRepository.count()).isEqualTo(1);

        assertThat(idempotencyKeyRepository.findById(idempotencyKey)).isPresent().get()
                .extracting(IdempotencyKey::getOrderId).isEqualTo(firstOrder.getId());

        verify(catalogClient, times(1)).reserveTicket(ticketId);

        verify(outboxService, times(1)).saveOrderCreatedEvent(any(Order.class), any(OrderEvent.class));
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentPayload() {
        String idempotencyKey = "test-key-different-payload";

        Long userId = 1L;
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();

        BigDecimal firstPrice = new BigDecimal("100.00");
        BigDecimal secondPrice = new BigDecimal("200.00");

        when(catalogClient.reserveTicket(ticketId)).thenReturn(true);

        Order firstOrder = orderService.createOrder(idempotencyKey, userId, eventId, ticketId, firstPrice);

        assertThat(firstOrder.getId()).isNotNull();

        assertThatThrownBy(() -> orderService.createOrder(idempotencyKey, userId, eventId, ticketId, secondPrice))
                .isInstanceOf(OrderConflictException.class);

        assertThat(orderRepository.count()).isEqualTo(1);

        Order persistedOrder = orderRepository.findById(firstOrder.getId()).orElseThrow();

        assertThat(persistedOrder.getTotalPrice()).isEqualByComparingTo(firstPrice);

        verify(catalogClient, times(1)).reserveTicket(ticketId);

        verify(outboxService, times(1)).saveOrderCreatedEvent(any(Order.class), any(OrderEvent.class));
    }

    @Test
    void shouldCreateOnlyOneOrderForConcurrentRequestsWithSameIdempotencyKey() throws Exception {

        String idempotencyKey = "test-key-concurrent";
        Long userId = 1L;
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("100.00");

        when(catalogClient.reserveTicket(ticketId)).thenReturn(true);

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

            // Все 20 запросов должны вернуть результат одного и того же заказа.
            assertThat(returnedOrders).hasSize(requestCount);

            Long createdOrderId = returnedOrders.getFirst().getId();

            assertThat(returnedOrders).extracting(Order::getId).containsOnly(createdOrderId);

            // Физически в БД должен существовать только один заказ.
            assertThat(orderRepository.count()).isEqualTo(1);

            // Idempotency-Key должен ссылаться на этот же заказ.
            assertThat(idempotencyKeyRepository.findById(idempotencyKey)).isPresent().get()
                    .extracting(IdempotencyKey::getOrderId).isEqualTo(createdOrderId);

            // Ticket должен быть зарезервирован только один раз.
            verify(catalogClient, times(1)).reserveTicket(ticketId);

            // Событие должно быть отправлено только для реально созданного заказа.
            verify(outboxService, times(1)).saveOrderCreatedEvent(any(Order.class), any(OrderEvent.class));

        } finally {
            executor.shutdownNow();
        }
    }

}