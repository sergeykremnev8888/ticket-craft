package ru.ticketcraft.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ru.ticketcraft.payment.outbox.PaymentOutboxEventType;

class OutboxMetricsTest {

    @Test
    void shouldRecordPublishedAndFailedEvents() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = new OutboxMetrics(registry);

        metrics.recordPublished(PaymentOutboxEventType.PAYMENT_SUCCEEDED, Duration.ofMillis(15));
        metrics.recordFailed(PaymentOutboxEventType.PAYMENT_SUCCEEDED);

        assertThat(registry.get("ticketcraft.outbox.events")
                .tag("event_type", "payment_succeeded")
                .tag("result", "published")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ticketcraft.outbox.events")
                .tag("event_type", "payment_succeeded")
                .tag("result", "failed")
                .counter().count()).isEqualTo(1.0);
    }
}
