package ru.ticketcraft.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ConsumerDuplicateMetricsTest {

    @Test
    void shouldRecordNotificationDuplicates() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConsumerDuplicateMetrics metrics = new ConsumerDuplicateMetrics(registry);

        metrics.recordDuplicate();

        assertThat(registry.get("ticketcraft.consumer.duplicates")
                .tag("consumer", "notification")
                .counter().count()).isEqualTo(1.0);
    }
}
