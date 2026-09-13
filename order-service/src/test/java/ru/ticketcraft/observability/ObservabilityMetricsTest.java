package ru.ticketcraft.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ru.ticketcraft.model.OutboxEventType;

class ObservabilityMetricsTest {

    @Test
    void shouldRecordOutboxMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = new OutboxMetrics(registry);

        metrics.recordPublished(OutboxEventType.PAYMENT_REQUESTED, Duration.ofMillis(10));
        metrics.recordFailed(OutboxEventType.PAYMENT_REQUESTED);

        assertThat(registry.get("ticketcraft.outbox.events")
                .tag("event_type", "payment_requested")
                .tag("result", "published")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ticketcraft.outbox.events")
                .tag("event_type", "payment_requested")
                .tag("result", "failed")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldRecordSagaAndDuplicateMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OrderSagaMetrics sagaMetrics = new OrderSagaMetrics(registry);
        ConsumerDuplicateMetrics duplicateMetrics = new ConsumerDuplicateMetrics(registry);

        sagaMetrics.recordCompletedAfterCommit();
        sagaMetrics.recordFailedAfterCommit();
        sagaMetrics.recordCompensatedAfterCommit();
        duplicateMetrics.recordReservationResultDuplicate();
        duplicateMetrics.recordPaymentResultDuplicate();

        assertThat(registry.get("ticketcraft.saga.completed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ticketcraft.saga.failed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ticketcraft.saga.compensated").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ticketcraft.consumer.duplicates")
                .tag("consumer", "reservation-results").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ticketcraft.consumer.duplicates")
                .tag("consumer", "payment-results").counter().count()).isEqualTo(1.0);
    }
}
