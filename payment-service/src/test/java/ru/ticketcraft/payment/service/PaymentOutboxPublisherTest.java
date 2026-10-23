package ru.ticketcraft.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import ru.ticketcraft.dto.PaymentSucceededEvent;
import ru.ticketcraft.payment.config.PaymentKafkaProperties;
import ru.ticketcraft.payment.config.PaymentOutboxProperties;
import ru.ticketcraft.payment.observability.OutboxMetrics;
import ru.ticketcraft.payment.outbox.PaymentOutboxEventType;
import ru.ticketcraft.payment.outbox.PaymentOutboxRecord;
import ru.ticketcraft.payment.outbox.PaymentOutboxRepository;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PaymentOutboxPublisherTest {

    private static final String RESULT_TOPIC = "payment-results";
    private static final String PAYLOAD = "{\"messageId\":\"payment-test\"}";
    private static final String CLAIM_ID = "instance-test:claim-test";

    private static final UUID OUTBOX_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final PaymentOutboxEventType EVENT_TYPE = PaymentOutboxEventType.PAYMENT_SUCCEEDED;

    @Mock
    private PaymentOutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private PaymentKafkaProperties kafkaProperties;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private OutboxMetrics outboxMetrics;

    @Mock
    private PaymentSucceededEvent event;

    private PaymentOutboxPublisher publisher;
    private PaymentOutboxRecord record;

    @BeforeEach
    void setUp() {
        PaymentOutboxProperties outboxProperties = 
                new PaymentOutboxProperties(100, Duration.ofSeconds(30), Duration.ofSeconds(1));

        publisher = new PaymentOutboxPublisher(outboxRepository, kafkaTemplate, kafkaProperties, outboxProperties,
                objectMapper, outboxMetrics);

        record = new PaymentOutboxRecord(OUTBOX_ID,
                                         "payment-test",
                                         123L,
                                         EVENT_TYPE.getPersistedValue(),
                                         PAYLOAD,
                                         Instant.parse("2026-01-01T10:00:00Z"),
                                         Instant.parse("2026-01-01T10:01:00Z"),
                                         CLAIM_ID);

        when(outboxRepository.claimBatch(anyInt(),
                                         anyString(),
                                         any(Instant.class),
                                         any(Instant.class))).thenReturn(List.of(record));
        when(objectMapper.readValue(PAYLOAD, PaymentSucceededEvent.class)).thenReturn(event);
        when(kafkaProperties.getResultTopic()).thenReturn(RESULT_TOPIC);
    }

    @Test
    void shouldMarkPublishedAfterKafkaAcknowledgment() {
        when(kafkaTemplate.send(RESULT_TOPIC, "123", event)).thenReturn(CompletableFuture.completedFuture(null));
        when(outboxRepository.markPublished(eq(OUTBOX_ID), eq(CLAIM_ID), any(Instant.class))).thenReturn(1);

        publisher.publish();

        verify(outboxRepository).markPublished(eq(OUTBOX_ID), eq(CLAIM_ID), any(Instant.class));
        verify(outboxMetrics).recordPublished(eq(EVENT_TYPE), any(Duration.class));
        verify(outboxMetrics, never()).recordFailed(any());
        verify(outboxRepository, never()).releaseClaim(any(), eq(CLAIM_ID));
    }

    @Test
    void shouldReleaseClaimWhenKafkaSendFails() {
        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.failedFuture(new IllegalStateException(
                "Kafka unavailable"));

        when(kafkaTemplate.send(RESULT_TOPIC, "123", event)).thenReturn(future);
        when(outboxRepository.releaseClaim(eq(OUTBOX_ID), eq(CLAIM_ID))).thenReturn(1);

        assertDoesNotThrow(publisher::publish);

        verify(outboxMetrics).recordFailed(EVENT_TYPE);
        verify(outboxRepository).releaseClaim(eq(OUTBOX_ID), eq(CLAIM_ID));
        verify(outboxRepository, never()).markPublished(any(), eq(CLAIM_ID), any(Instant.class));
        verify(outboxMetrics, never()).recordPublished(any(), any(Duration.class));
    }

    @Test
    void shouldReleaseClaimWhenSendThrowsSynchronously() {
        when(kafkaTemplate.send(RESULT_TOPIC, "123", event)).thenThrow(new IllegalStateException("Producer closed"));
        when(outboxRepository.releaseClaim(eq(OUTBOX_ID), eq(CLAIM_ID))).thenReturn(1);

        assertDoesNotThrow(publisher::publish);

        verify(outboxMetrics).recordFailed(EVENT_TYPE);
        verify(outboxRepository).releaseClaim(eq(OUTBOX_ID),eq(CLAIM_ID));
        verify(outboxRepository, never()).markPublished(any(), eq(CLAIM_ID), any(Instant.class));
    }

    @Test
    void shouldLeaveClaimToExpireWhenMarkPublishedFails() {
        when(kafkaTemplate.send(RESULT_TOPIC, "123", event)).thenReturn(CompletableFuture.completedFuture(null));
        when(outboxRepository.markPublished(eq(OUTBOX_ID),
                                            eq(CLAIM_ID),
                                            any(Instant.class))).thenThrow(new IllegalStateException(
                                                    "Database unavailable"));

        assertDoesNotThrow(publisher::publish);

        verify(outboxRepository).markPublished(eq(OUTBOX_ID), eq(CLAIM_ID), any(Instant.class));

        // Kafka подтвердила отправку, несмотря на ошибку записи в БД.
        verify(outboxMetrics).recordPublished(eq(EVENT_TYPE), any(Duration.class));

        verify(outboxMetrics, never()).recordFailed(any());

        // Не освобождаем захват сразу после подтверждённой отправки.
        verify(outboxRepository, never()).releaseClaim(any(), eq(CLAIM_ID));
    }

    @Test
    void shouldNotPropagateReleaseClaimFailure() {
        when(kafkaTemplate.send(RESULT_TOPIC,
                                "123",
                                event)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));
        when(outboxRepository.releaseClaim(eq(OUTBOX_ID), eq(CLAIM_ID))).thenThrow(new IllegalStateException(
                "Database unavailable"));

        assertDoesNotThrow(publisher::publish);

        verify(outboxMetrics).recordFailed(EVENT_TYPE);
        verify(outboxRepository).releaseClaim(eq(OUTBOX_ID), eq(CLAIM_ID));
        verify(outboxRepository, never()).markPublished(any(), eq(CLAIM_ID), any(Instant.class));
    }

    @Test
    void shouldWaitForKafkaAndUpdateDatabaseInPublisherThread() throws Exception {
        AwaitedFuture future = new AwaitedFuture();

        AtomicReference<Thread> publisherThread = new AtomicReference<>();
        AtomicReference<Thread> databaseThread = new AtomicReference<>();

        when(kafkaTemplate.send(RESULT_TOPIC, "123", event)).thenReturn(future);
        when(outboxRepository.markPublished(eq(OUTBOX_ID), eq(CLAIM_ID), any(Instant.class))).thenAnswer(invocation -> {
            databaseThread.set(Thread.currentThread());
            return 1;
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> task = executor.submit(() -> {
                publisherThread.set(Thread.currentThread());
                publisher.publish();
            });

            assertThat(future.awaitGet()).isTrue();

            verify(outboxRepository, never()).markPublished(any(), eq(CLAIM_ID), any(Instant.class));

            assertThat(task.isDone()).isFalse();

            // Имитируем подтверждение из другого потока — потока producer.
            future.complete(null);

            task.get(5, TimeUnit.SECONDS);

            assertThat(databaseThread.get()).isSameAs(publisherThread.get());
            assertThat(databaseThread.get()).isNotSameAs(Thread.currentThread());

        } finally {
            future.complete(null);
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRestoreInterruptFlagAndLeaveClaimToExpire() throws Exception {
        AwaitedFuture future = new AwaitedFuture();

        AtomicBoolean interruptedAfterPublish = new AtomicBoolean();
        AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();

        when(kafkaTemplate.send(RESULT_TOPIC, "123", event)).thenReturn(future);

        Thread worker = new Thread(() -> {
            try {
                publisher.publish();

                interruptedAfterPublish.set(Thread.currentThread().isInterrupted());

            } catch (Throwable exception) {
                unexpectedFailure.set(exception);
            }
        }, "outbox-test-worker");

        worker.start();

        try {
            assertThat(future.awaitGet()).isTrue();

            worker.interrupt();
            worker.join(5_000);

            assertThat(worker.isAlive()).isFalse();
            assertThat(unexpectedFailure.get()).isNull();
            assertThat(interruptedAfterPublish.get()).isTrue();

            verify(outboxRepository, never()).markPublished(any(), eq(CLAIM_ID), any(Instant.class));
            verify(outboxRepository, never()).releaseClaim(any(), eq(CLAIM_ID));
            verify(outboxMetrics, never()).recordPublished(any(), any(Duration.class));
            verify(outboxMetrics, never()).recordFailed(any());

        } finally {
            future.complete(null);
            worker.interrupt();
            worker.join(5_000);
        }
    }

    private static final class AwaitedFuture extends CompletableFuture<SendResult<String, Object>> {
        private final CountDownLatch getCalled = new CountDownLatch(1);

        @Override
        public SendResult<String, Object> get() throws InterruptedException, ExecutionException {
            getCalled.countDown();
            return super.get();
        }

        boolean awaitGet() throws InterruptedException {
            return getCalled.await(5, TimeUnit.SECONDS);
        }
    }
}