package ru.ticketcraft.payment.observability;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import ru.ticketcraft.payment.outbox.PaymentOutboxEventType;

@Component
public class OutboxMetrics {

    private final Map<PaymentOutboxEventType, Counter> publishedCounters = new EnumMap<>(PaymentOutboxEventType.class);
    private final Map<PaymentOutboxEventType, Counter> failedCounters = new EnumMap<>(PaymentOutboxEventType.class);
    private final Map<PaymentOutboxEventType, Timer> publishTimers = new EnumMap<>(PaymentOutboxEventType.class);

    public OutboxMetrics(MeterRegistry meterRegistry) {
        for (PaymentOutboxEventType eventType : PaymentOutboxEventType.values()) {
            String tag = eventType.name().toLowerCase();
            publishedCounters.put(eventType, Counter.builder("ticketcraft.outbox.events")
                    .description("Number of processed outbox events")
                    .tag("event_type", tag)
                    .tag("result", "published")
                    .register(meterRegistry));
            failedCounters.put(eventType, Counter.builder("ticketcraft.outbox.events")
                    .description("Number of processed outbox events")
                    .tag("event_type", tag)
                    .tag("result", "failed")
                    .register(meterRegistry));
            publishTimers.put(eventType, Timer.builder("ticketcraft.outbox.publish.duration")
                    .description("Outbox event publishing duration")
                    .tag("event_type", tag)
                    .register(meterRegistry));
        }
    }

    public void recordPublished(PaymentOutboxEventType eventType, Duration duration) {
        publishedCounters.get(eventType).increment();
        publishTimers.get(eventType).record(duration);
    }

    public void recordFailed(PaymentOutboxEventType eventType) {
        failedCounters.get(eventType).increment();
    }
}
