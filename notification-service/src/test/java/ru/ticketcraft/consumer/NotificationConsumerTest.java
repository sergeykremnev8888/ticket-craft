package ru.ticketcraft.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.support.Acknowledgment;

import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.service.IdempotentNotificationProcessor;

class NotificationConsumerTest {

    private static final String MESSAGE_ID = "11111111-1111-1111-1111-111111111111";

    private static final UUID EVENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TICKET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Long ORDER_ID = 123L;
    private static final Long USER_ID = 10L;

    private static final BigDecimal TOTAL_PRICE = new BigDecimal("100.00");

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private Acknowledgment acknowledgment;

    @Mock
    private IdempotentNotificationProcessor processor;

    private NotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        consumer = new NotificationConsumer(processor);
    }

    @Test
    void shouldDelegateEventToProcessor() {
        OrderEvent event = createEvent();

        consumer.listen(event, MESSAGE_ID, 0, 42L, acknowledgment);

        verify(processor).process(event);
    }

    @Test
    void shouldProcessEventAndAcknowledge() {
        OrderEvent event = createEvent();

        consumer.listen(event, MESSAGE_ID, 0, 42L, acknowledgment);

        verify(processor).process(event);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldNotAcknowledgeWhenProcessingFails() {
        OrderEvent event = createEvent();

        doThrow(new RuntimeException("Processing failed")).when(processor).process(event);

        assertThatThrownBy(() -> consumer.listen(event, MESSAGE_ID, 0, 42L, acknowledgment))
                .isInstanceOf(RuntimeException.class).hasMessage("Processing failed");

        verify(processor).process(event);
        verify(acknowledgment, never()).acknowledge();
    }

    private OrderEvent createEvent() {
        return new OrderEvent(MESSAGE_ID, ORDER_ID, USER_ID, EVENT_ID, List.of(TICKET_ID), TOTAL_PRICE,
                OrderState.CREATED, CREATED_AT);
    }
}