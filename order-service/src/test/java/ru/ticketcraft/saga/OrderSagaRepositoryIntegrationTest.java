package ru.ticketcraft.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.OrderRepository;
import ru.ticketcraft.repository.OrderSagaRepository;

@Testcontainers
@DataJdbcTest
class OrderSagaRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("order_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private OrderSagaRepository orderSagaRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void shouldInsertSaga() {
        Long orderId = createOrder();
        UUID sagaId = UUID.randomUUID();
        Instant now = Instant.now();

        int inserted = orderSagaRepository.insertIfAbsent(sagaId, orderId,
                OrderSagaStatus.WAITING_FOR_RESERVATION.name(), now, now);

        assertThat(inserted).isEqualTo(1);

        OrderSaga saga = orderSagaRepository.findByOrderId(orderId).orElseThrow();

        assertThat(saga.getId()).isEqualTo(sagaId);
        assertThat(saga.getOrderId()).isEqualTo(orderId);
        assertThat(saga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_RESERVATION);
        assertThat(saga.getCreatedAt()).isNotNull();
        assertThat(saga.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldNotCreateSecondSagaForSameOrder() {
        Long orderId = createOrder();
        Instant now = Instant.now();

        UUID firstSagaId = UUID.randomUUID();
        UUID secondSagaId = UUID.randomUUID();

        int firstInsert = orderSagaRepository.insertIfAbsent(firstSagaId, orderId,
                OrderSagaStatus.WAITING_FOR_RESERVATION.name(), now, now);

        int secondInsert = orderSagaRepository.insertIfAbsent(secondSagaId, orderId,
                OrderSagaStatus.WAITING_FOR_RESERVATION.name(), now, now);

        assertThat(firstInsert).isEqualTo(1);
        assertThat(secondInsert).isZero();

        OrderSaga saga = orderSagaRepository.findByOrderId(orderId).orElseThrow();

        assertThat(saga.getId()).isEqualTo(firstSagaId);
        assertThat(saga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_RESERVATION);
    }

    @Test
    void shouldTransitionSagaWhenExpectedStatusMatches() {
        Long orderId = createOrder();
        Instant now = Instant.now();

        orderSagaRepository.insertIfAbsent(UUID.randomUUID(), orderId, OrderSagaStatus.WAITING_FOR_RESERVATION.name(),
                now, now);

        Instant transitionTime = Instant.now();

        int updated = orderSagaRepository.transition(orderId, OrderSagaStatus.WAITING_FOR_RESERVATION.name(),
                OrderSagaStatus.WAITING_FOR_PAYMENT.name(), transitionTime);

        assertThat(updated).isEqualTo(1);

        OrderSaga saga = orderSagaRepository.findByOrderId(orderId).orElseThrow();

        assertThat(saga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_PAYMENT);

        assertThat(saga.getUpdatedAt()).isCloseTo(transitionTime, within(1, ChronoUnit.MICROS));
    }

    @Test
    void shouldNotTransitionSagaWhenExpectedStatusDoesNotMatch() {
        Long orderId = createOrder();
        Instant now = Instant.now();

        orderSagaRepository.insertIfAbsent(UUID.randomUUID(), orderId, OrderSagaStatus.WAITING_FOR_RESERVATION.name(),
                now, now);

        int updated = orderSagaRepository.transition(orderId, OrderSagaStatus.WAITING_FOR_PAYMENT.name(),
                OrderSagaStatus.COMPLETED.name(), Instant.now());

        assertThat(updated).isZero();

        OrderSaga saga = orderSagaRepository.findByOrderId(orderId).orElseThrow();

        assertThat(saga.getStatus()).isEqualTo(OrderSagaStatus.WAITING_FOR_RESERVATION);
    }

    private Long createOrder() {
        Order order = new Order(null, 100L, UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("150.00"),
                OrderState.CREATED, Instant.now());

        Order savedOrder = orderRepository.save(order);

        return savedOrder.getId();
    }
}