package ru.ticketcraft.order.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ru.ticketcraft.order.observability.ConsumerDuplicateMetrics;
import ru.ticketcraft.order.observability.OrderSagaMetrics;

class ObservabilityMetricsTest {

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
