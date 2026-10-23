package ru.ticketcraft.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ticketcraft.kafka.retry")
public record PaymentRetryProperties(@DefaultValue("3") int maxAttempts,
                                     @DefaultValue("1s") Duration backOff) {

    public PaymentRetryProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("ticketcraft.kafka.retry.max-attempts must be at least 1");
        }

        if (backOff == null || backOff.isNegative()) {
            throw new IllegalArgumentException("ticketcraft.kafka.retry.back-off must not be negative");
        }

        if (!backOff.isZero() && backOff.toMillis() == 0) {
            throw new IllegalArgumentException(
                    "ticketcraft.kafka.retry.back-off must be zero " + "or at least 1 millisecond");
        }
    }
}