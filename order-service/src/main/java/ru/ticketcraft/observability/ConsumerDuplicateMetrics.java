package ru.ticketcraft.observability;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class ConsumerDuplicateMetrics {

    private final Counter reservationResults;
    private final Counter paymentResults;

    public ConsumerDuplicateMetrics(MeterRegistry meterRegistry) {
        reservationResults = Counter.builder("ticketcraft.consumer.duplicates")
                .description("Duplicate Kafka messages skipped by persistent consumer idempotency")
                .tag("consumer", "reservation-results")
                .register(meterRegistry);
        paymentResults = Counter.builder("ticketcraft.consumer.duplicates")
                .description("Duplicate Kafka messages skipped by persistent consumer idempotency")
                .tag("consumer", "payment-results")
                .register(meterRegistry);
    }

    public void recordReservationResultDuplicate() {
        reservationResults.increment();
    }

    public void recordPaymentResultDuplicate() {
        paymentResults.increment();
    }
}
