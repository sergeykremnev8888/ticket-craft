package ru.ticketcraft.observability;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class OrderSagaMetrics {

    private final Counter completed;
    private final Counter failed;
    private final Counter compensated;

    public OrderSagaMetrics(MeterRegistry meterRegistry) {
        completed = Counter.builder("ticketcraft.saga.completed")
                .description("Successfully completed order sagas")
                .register(meterRegistry);
        failed = Counter.builder("ticketcraft.saga.failed")
                .description("Order sagas that failed before payment completion")
                .register(meterRegistry);
        compensated = Counter.builder("ticketcraft.saga.compensated")
                .description("Order sagas completed through reservation compensation")
                .register(meterRegistry);
    }

    public void recordCompletedAfterCommit() {
        incrementAfterCommit(completed);
    }

    public void recordFailedAfterCommit() {
        incrementAfterCommit(failed);
    }

    public void recordCompensatedAfterCommit() {
        incrementAfterCommit(compensated);
    }

    private static void incrementAfterCommit(Counter counter) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            counter.increment();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                counter.increment();
            }
        });
    }
}
