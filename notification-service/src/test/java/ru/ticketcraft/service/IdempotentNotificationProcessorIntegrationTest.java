package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.repository.ProcessedEventRepository;

@DataJdbcTest
@Testcontainers
@Import({ ProcessedEventRepository.class, IdempotentNotificationProcessor.class })
class IdempotentNotificationProcessorIntegrationTest {

    private static final String MESSAGE_ID = "rollback-message-001";

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("notification_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private IdempotentNotificationProcessor processor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM processed_events");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRollbackProcessedEventWhenNotificationFails() {
        OrderEvent event = org.mockito.Mockito.mock(OrderEvent.class);

        when(event.getMessageId()).thenReturn(MESSAGE_ID);

        doThrow(new RuntimeException("Notification service unavailable")).when(notificationService).process(event);

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(RuntimeException.class)
                .hasMessage("Notification service unavailable");

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM processed_events
                WHERE message_id = ?
                """, Integer.class, MESSAGE_ID);

        assertThat(count).isZero();
    }
}