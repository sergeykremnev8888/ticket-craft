package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.IdempotencyKeyRepository;
import ru.ticketcraft.repository.OrderRepository;
import ru.ticketcraft.repository.OrderSagaRepository;
import ru.ticketcraft.repository.OutboxEventRepository;

@Testcontainers
@SpringBootTest(properties = { "ticketcraft.outbox.publisher.enabled=false" })
class OrderSagaTransactionRollbackIntegrationTest {

    private static final String IDEMPOTENCY_KEY = "order-saga-rollback-key";

    private static final Long USER_ID = 10L;

    private static final UUID EVENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID TICKET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

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

    /*
     * Только OutboxService заменяем Mockito mock-ом.
     *
     * Сам OrderService остаётся настоящим Spring bean, поэтому @Transactional proxy
     * продолжает работать.
     */
    @MockitoBean
    private OutboxService outboxService;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        orderSagaRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    void shouldRollbackOrderSagaAndIdempotencyWhenOutboxCreationFails() {

        doThrow(new IllegalStateException("Simulated outbox failure")).when(outboxService)
                .saveReserveTicketCommand(any(Order.class), any(ReserveTicketCommand.class));

        assertThatThrownBy(() -> orderService.createOrder(IDEMPOTENCY_KEY, USER_ID, EVENT_ID, TICKET_ID, PRICE))
                .isInstanceOf(IllegalStateException.class).hasMessage("Simulated outbox failure");

        /*
         * Самое важное:
         *
         * Order был INSERT-нут раньше outbox вызова, но после rollback его в БД быть не
         * должно.
         */
        assertThat(orderRepository.count()).isZero();

        /*
         * Saga также была INSERT-нута перед outbox, но обязана откатиться.
         */
        assertThat(orderSagaRepository.count()).isZero();

        /*
         * Idempotency registration тоже находится внутри createOrder transaction.
         *
         * PROCESSING запись не должна "зависнуть" после rollback.
         */
        assertThat(idempotencyKeyRepository.findById(IDEMPOTENCY_KEY)).isEmpty();

        /*
         * OutboxService был mock-ом и упал до insert, поэтому никаких outbox rows нет.
         */
        assertThat(outboxEventRepository.count()).isZero();
    }
}