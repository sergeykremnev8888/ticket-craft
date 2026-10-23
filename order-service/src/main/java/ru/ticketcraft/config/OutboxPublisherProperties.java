package ru.ticketcraft.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketcraft.outbox.publisher")
public record OutboxPublisherProperties(boolean enabled,
                                        int batchSize,
                                        Duration fixedDelay,
                                        Duration lockDuration,
                                        Duration retryDelay,
                                        Duration sendTimeout) {

    public OutboxPublisherProperties {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "ticketcraft.outbox.publisher.batch-size must be positive");
        }

        validatePositiveDuration(fixedDelay, "fixed-delay");
        validatePositiveDuration(lockDuration, "lock-duration");
        validatePositiveDuration(retryDelay, "retry-delay");
        validatePositiveDuration(sendTimeout, "send-timeout");

        if (lockDuration.compareTo(sendTimeout) <= 0) {
            throw new IllegalArgumentException(
                    "ticketcraft.outbox.publisher.lock-duration "
                            + "must be greater than send-timeout");
        }
    }

    private static void validatePositiveDuration(Duration value, String property) {
        String propertyName = "ticketcraft.outbox.publisher." + property;

        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }

        if (value.toMillis() == 0) {
            throw new IllegalArgumentException(propertyName + " must be at least 1 millisecond");
        }
    }
}