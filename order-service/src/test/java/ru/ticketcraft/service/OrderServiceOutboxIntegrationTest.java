package ru.ticketcraft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.client.CatalogClient;
import ru.ticketcraft.model.IdempotencyStatus;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.model.OutboxEvent;
import ru.ticketcraft.repository.IdempotencyKeyRepository;
import ru.ticketcraft.repository.OrderRepository;
import ru.ticketcraft.repository.OutboxEventRepository;

@Testcontainers
@SpringBootTest
class OrderServiceOutboxIntegrationTest {

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
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private CatalogClient catalogClient;

    @Test
    void shouldCreateOrderAndOutboxEventInSameTransaction() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();

        when(catalogClient.reserveTicket(ticketId)).thenReturn(true);

        Order order = orderService.createOrder("integration-test-key", 42L, eventId, ticketId,
                new BigDecimal("100.00"));

        assertNotNull(order.getId());

        assertEquals(1, orderRepository.count());

        List<OutboxEvent> outboxEvents = StreamSupport
                .stream(outboxEventRepository.findAll().spliterator(), false)
                .toList();

        assertEquals(1, outboxEvents.size());

        OutboxEvent outboxEvent = outboxEvents.iterator().next();

        assertEquals("ORDER", outboxEvent.getAggregateType());
        assertEquals(order.getId().toString(), outboxEvent.getAggregateId());
        assertEquals("OrderCreated", outboxEvent.getEventType());
        assertEquals("PENDING", outboxEvent.getStatus().name());

        var idempotencyKey = idempotencyKeyRepository.findById("integration-test-key").orElseThrow();

        assertEquals(IdempotencyStatus.COMPLETED, idempotencyKey.getStatus());

        assertEquals(order.getId(), idempotencyKey.getOrderId());

        verify(catalogClient).reserveTicket(ticketId);
    }
}