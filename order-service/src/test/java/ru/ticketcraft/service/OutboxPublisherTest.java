package ru.ticketcraft.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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

    private static final UUID CLAIM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private OutboxClaimService claimService;

    @Mock
    private KafkaTemplate<String, OrderEvent> kafkaTemplate;

    private ObjectMapper objectMapper;
    private OutboxPublisherProperties properties;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        objectMapper = new ObjectMapper();

        properties = new OutboxPublisherProperties(true, 100, Duration.ofSeconds(1), Duration.ofSeconds(30),
                Duration.ofSeconds(5), Duration.ofSeconds(10), "order-events");

        publisher = new OutboxPublisher(claimService, kafkaTemplate, objectMapper, properties);
    }

    @Test
    void shouldPublishClaimedEvent() throws Exception {
        OutboxEvent event = createEvent();

        when(claimService.claimPending(any(UUID.class), any(Instant.class), any(Instant.class), any(Instant.class),
                any(String.class), eq(100))).thenReturn(1);

        when(claimService.findClaimed(any(UUID.class))).thenAnswer(invocation -> {
            UUID claimId = invocation.getArgument(0);

            return List.of(createEventWithClaim(claimId));
        });

        CompletableFuture<SendResult<String, OrderEvent>> future = CompletableFuture.completedFuture(null);

        when(kafkaTemplate.send(eq("order-events"), eq("123"), any(OrderEvent.class))).thenReturn(future);

        when(claimService.markPublished(eq(EVENT_ID), any(UUID.class))).thenReturn(true);

        publisher.publishPendingEvents();

        verify(kafkaTemplate).send(eq("order-events"), eq("123"), any(OrderEvent.class));

        verify(claimService).markPublished(eq(EVENT_ID), any(UUID.class));

        verify(claimService, never()).releaseClaim(eq(EVENT_ID), any(UUID.class), any(Instant.class));
    }

    @Test
    void shouldReleaseClaimWhenKafkaSendFails() throws Exception {

        when(claimService.claimPending(any(UUID.class), any(Instant.class), any(Instant.class), any(Instant.class),
                any(String.class), eq(100))).thenReturn(1);

        when(claimService.findClaimed(any(UUID.class))).thenAnswer(invocation -> {
            UUID claimId = invocation.getArgument(0);

            return List.of(createEventWithClaim(claimId));
        });

        CompletableFuture<SendResult<String, OrderEvent>> future = new CompletableFuture<>();

        future.completeExceptionally(new IllegalStateException("Kafka unavailable"));

        when(kafkaTemplate.send(eq("order-events"), eq("123"), any(OrderEvent.class))).thenReturn(future);

        when(claimService.releaseClaim(eq(EVENT_ID), any(UUID.class), any(Instant.class))).thenReturn(true);

        assertDoesNotThrow(() -> publisher.publishPendingEvents());

        verify(claimService).releaseClaim(eq(EVENT_ID), any(UUID.class), any(Instant.class));

        verify(claimService, never()).markPublished(eq(EVENT_ID), any(UUID.class));
    }

    @Test
    void shouldDoNothingWhenNoEventsClaimed() {
        when(claimService.claimPending(any(UUID.class), any(Instant.class), any(Instant.class), any(Instant.class),
                any(String.class), eq(100))).thenReturn(0);

        publisher.publishPendingEvents();

        verify(claimService, never()).findClaimed(any(UUID.class));

        verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any(OrderEvent.class));
    }

    @Test
    void shouldDeserializeOrderEvent() throws Exception {
        OrderEvent event = new OrderEvent(UUID.randomUUID().toString(), 123L, 10L, UUID.randomUUID(),
                List.of(UUID.randomUUID()), new BigDecimal("100.00"), OrderState.CREATED,
                Instant.parse("2026-01-01T10:00:00Z"));

        String payload = objectMapper.writeValueAsString(event);

        OrderEvent restored = objectMapper.readValue(payload, OrderEvent.class);

        assertEquals(event.getOrderId(), restored.getOrderId());
        assertEquals(event.getUserId(), restored.getUserId());
        assertEquals(event.getEventId(), restored.getEventId());
        assertEquals(event.getTicketIds(), restored.getTicketIds());
        assertEquals(event.getTotalPrice(), restored.getTotalPrice());
        assertEquals(event.getState(), restored.getState());
    }

    private OutboxEvent createEvent() {
        return createEventWithClaim(CLAIM_ID);
    }

    private OutboxEvent createEventWithClaim(UUID claimId) {
        String payload = """
                {
                  "eventId": "33333333-3333-3333-3333-333333333333",
                  "orderId": 123,
                  "userId": 10,
                  "eventId": "44444444-4444-4444-4444-444444444444",
                  "ticketIds": [
                    "55555555-5555-5555-5555-555555555555"
                  ],
                  "totalPrice": 100.00,
                  "status": "CREATED",
                  "createdAt": "2026-01-01T10:00:00Z"
                }
                """;

        return new OutboxEvent(EVENT_ID, "ORDER", "123", "OrderCreated", payload, OutboxStatus.PENDING, CREATED_AT,
                null, 1, CREATED_AT, CREATED_AT, "publisher-1", claimId);
    }
}