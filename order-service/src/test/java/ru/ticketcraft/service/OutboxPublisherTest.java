package ru.ticketcraft.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import ru.ticketcraft.config.OutboxPublisherProperties;
import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.model.OutboxEvent;
import ru.ticketcraft.model.OutboxStatus;
import tools.jackson.databind.ObjectMapper;

class OutboxPublisherTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TICKET_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private static final Long ORDER_ID = 123L;
    private static final Long USER_ID = 10L;

    private static final BigDecimal TOTAL_PRICE = new BigDecimal("100.00");

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");

    private static final int BATCH_SIZE = 10;

    private static final String TOPIC = "order-events";

    @Mock
    private OutboxClaimService claimService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private ObjectMapper objectMapper;
    private OutboxPublisherProperties properties;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        objectMapper = new ObjectMapper();

        properties = new OutboxPublisherProperties(
                true,
                BATCH_SIZE,
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                TOPIC
        );

        publisher = new OutboxPublisher(claimService, kafkaTemplate, objectMapper, properties);
    }

    @Test
    void shouldPublishClaimedEvent() throws Exception {
        OrderEvent expectedEvent = new OrderEvent(EVENT_ID.toString(), ORDER_ID, USER_ID, EVENT_ID, List.of(TICKET_ID),
                TOTAL_PRICE, OrderState.CREATED, CREATED_AT);

        String payload = objectMapper.writeValueAsString(expectedEvent);

        when(claimService.claimPending(
                any(UUID.class),
                any(Instant.class),
                any(Instant.class),
                any(Instant.class),
                anyString(),
                eq(BATCH_SIZE)
        )).thenReturn(1);

        when(claimService.findClaimed(any(UUID.class))).thenAnswer(invocation -> {
            UUID claimId = invocation.getArgument(0);

            OutboxEvent claimedEvent = new OutboxEvent(EVENT_ID, "ORDER", ORDER_ID.toString(), "OrderCreated", payload,
                    OutboxStatus.PENDING, CREATED_AT, null, 1, CREATED_AT, CREATED_AT, "publisher-1", claimId);

            return List.of(claimedEvent);
        });

        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(null);

        when(kafkaTemplate.send(eq(TOPIC), eq(ORDER_ID.toString()), any(OrderEvent.class))).thenReturn(future);

        when(claimService.markPublished(eq(EVENT_ID), any(UUID.class))).thenReturn(true);

        publisher.publishPendingEvents();

        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);

        verify(kafkaTemplate).send(eq(TOPIC), eq(ORDER_ID.toString()), eventCaptor.capture());

        OrderEvent actualEvent = eventCaptor.getValue();

        assertEquals(expectedEvent.getMessageId(), actualEvent.getMessageId());
        assertEquals(expectedEvent.getOrderId(), actualEvent.getOrderId());
        assertEquals(expectedEvent.getUserId(), actualEvent.getUserId());
        assertEquals(expectedEvent.getEventId(), actualEvent.getEventId());
        assertEquals(expectedEvent.getTicketIds(), actualEvent.getTicketIds());
        assertEquals(expectedEvent.getTotalPrice(), actualEvent.getTotalPrice());
        assertEquals(expectedEvent.getState(), actualEvent.getState());
        assertEquals(expectedEvent.getCreatedAt(), actualEvent.getCreatedAt());

        verify(claimService).markPublished(eq(EVENT_ID), any(UUID.class));

        verify(claimService, never()).releaseClaim(eq(EVENT_ID), any(UUID.class), any(Instant.class));
    }

    @Test
    void shouldReleaseClaimWhenKafkaSendFails() throws Exception {
        when(claimService.claimPending(
                any(UUID.class),
                any(Instant.class),
                any(Instant.class),
                any(Instant.class),
                any(String.class),
                eq(BATCH_SIZE)
        )).thenReturn(1);

        when(claimService.findClaimed(any(UUID.class))).thenAnswer(invocation -> {
            UUID claimId = invocation.getArgument(0);

            return List.of(createEventWithClaim(claimId));
        });

        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();

        future.completeExceptionally(new IllegalStateException("Kafka unavailable"));

        when(kafkaTemplate.send(eq("order-events"), eq("123"), any(OrderEvent.class))).thenReturn(future);

        when(claimService.releaseClaim(eq(EVENT_ID), any(UUID.class), any(Instant.class))).thenReturn(true);

        assertDoesNotThrow(() -> publisher.publishPendingEvents());

        verify(claimService).releaseClaim(eq(EVENT_ID), any(UUID.class), any(Instant.class));

        verify(claimService, never()).markPublished(eq(EVENT_ID), any(UUID.class));
    }

    @Test
    void shouldDoNothingWhenNoEventsClaimed() {
        when(claimService.claimPending(
                any(UUID.class),
                any(Instant.class),
                any(Instant.class),
                any(Instant.class),
                any(String.class),
                eq(BATCH_SIZE)
        )).thenReturn(0);

        publisher.publishPendingEvents();

        verify(claimService, never()).findClaimed(any(UUID.class));

        verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any(OrderEvent.class));
    }

    private OutboxEvent createEventWithClaim(UUID claimId) throws Exception {
        UUID eventId = EVENT_ID;
        UUID ticketId = UUID.fromString("55555555-5555-5555-5555-555555555555");

        OrderEvent orderEvent = new OrderEvent(eventId.toString(), 123L, 10L, eventId, List.of(ticketId),
                new BigDecimal("100.00"), OrderState.CREATED, CREATED_AT);

        String payload = objectMapper.writeValueAsString(orderEvent);

        return new OutboxEvent(eventId, "ORDER", "123", "OrderCreated", payload, OutboxStatus.PENDING, CREATED_AT, null,
                1, CREATED_AT, CREATED_AT, "publisher-1", claimId);
    }
}