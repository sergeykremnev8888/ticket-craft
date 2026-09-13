package ru.ticketcraft.observability;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class ConsumerDuplicateMetrics {

    private final Counter notificationDuplicates;

    public ConsumerDuplicateMetrics(MeterRegistry meterRegistry) {
        notificationDuplicates = Counter.builder("ticketcraft.consumer.duplicates")
                .description("Duplicate Kafka messages skipped by persistent consumer idempotency")
                .tag("consumer", "notification")
                .register(meterRegistry);
    }

    public void recordDuplicate() {
        notificationDuplicates.increment();
    }
}
