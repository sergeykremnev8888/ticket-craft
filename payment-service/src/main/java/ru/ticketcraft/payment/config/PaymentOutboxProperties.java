package ru.ticketcraft.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ticketcraft.outbox")
public record PaymentOutboxProperties(@DefaultValue("100") int batchSize,
                                      @DefaultValue("30s") Duration claimLease,
                                      @DefaultValue("1s") Duration publishDelay) {

    public PaymentOutboxProperties {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("ticketcraft.outbox.batch-size must be positive");
        }

        validatePositiveDuration(claimLease, "ticketcraft.outbox.claim-lease");
        validatePositiveDuration(publishDelay, "ticketcraft.outbox.publish-delay");
    }

    private void validatePositiveDuration(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be positive");
        }

        if (value.toMillis() == 0) {
            throw new IllegalArgumentException(property + " must be at least 1 millisecond");
        }
    }
}