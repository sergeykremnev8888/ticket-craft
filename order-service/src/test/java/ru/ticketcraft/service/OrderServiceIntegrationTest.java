package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.client.CatalogClient;
import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.repository.IdempotencyKeyRepository;
import ru.ticketcraft.repository.OrderRepository;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:9092"
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
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CatalogClient catalogClient;

    @MockitoBean
    private KafkaTemplate<String, OrderEvent> kafkaTemplate;

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
}