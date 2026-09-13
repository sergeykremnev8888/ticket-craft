package ru.ticketcraft.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ru.ticketcraft.model.OutboxEventType;

class OutboxMetricsTest {

    @Test
    void shouldRecordPublishedAndFailedEvents() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = new OutboxMetrics(registry);

        metrics.recordPublished(OutboxEventType.TICKET_RESERVED, Duration.ofMillis(25));
        metrics.recordFailed(OutboxEventType.TICKET_RESERVED);

        assertThat(registry.get("ticketcraft.outbox.events")
                .tag("event_type", "ticket_reserved")
                .tag("result", "published")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ticketcraft.outbox.events")
                .tag("event_type", "ticket_reserved")
                .tag("result", "failed")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ticketcraft.outbox.publish.duration")
                .tag("event_type", "ticket_reserved")
                .timer().count()).isEqualTo(1L);
    }
}
