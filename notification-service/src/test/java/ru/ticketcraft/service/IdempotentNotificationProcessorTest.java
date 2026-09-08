package ru.ticketcraft.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.repository.ProcessedEventRepository;

class IdempotentNotificationProcessorTest {

    private static final String MESSAGE_ID = "11111111-1111-1111-1111-111111111111";

    private static final UUID EVENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TICKET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Long ORDER_ID = 123L;
    private static final Long USER_ID = 10L;

    private static final BigDecimal TOTAL_PRICE = new BigDecimal("100.00");

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private NotificationService notificationService;

    private IdempotentNotificationProcessor processor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        processor = new IdempotentNotificationProcessor(processedEventRepository, notificationService);
    }

    @Test
    void shouldProcessNewEvent() {
        OrderEvent event = createEvent();

        when(processedEventRepository.insertIfAbsent(MESSAGE_ID)).thenReturn(1);

        processor.process(event);

        verify(processedEventRepository).insertIfAbsent(MESSAGE_ID);

        verify(notificationService).process(event);
    }

    @Test
    void shouldIgnoreDuplicateEvent() {
        OrderEvent event = createEvent();

        when(processedEventRepository.insertIfAbsent(MESSAGE_ID)).thenReturn(0);

        processor.process(event);

        verify(processedEventRepository).insertIfAbsent(MESSAGE_ID);

        verify(notificationService, never()).process(event);
    }

    @Test
    void shouldUseMessageIdAsIdempotencyKey() {
        OrderEvent event = createEvent();

        when(processedEventRepository.insertIfAbsent(MESSAGE_ID)).thenReturn(1);

        processor.process(event);

        verify(processedEventRepository).insertIfAbsent(event.getMessageId());

        verify(notificationService).process(event);
    }

    private OrderEvent createEvent() {
        return new OrderEvent(MESSAGE_ID, ORDER_ID, USER_ID, EVENT_ID, List.of(TICKET_ID), TOTAL_PRICE,
                OrderState.CREATED, CREATED_AT);
    }
}