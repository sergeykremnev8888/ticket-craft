package ru.ticketcraft.ratelimit;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketcraft.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        Policy catalogRead,
        Policy reservation) {

    public RateLimitProperties {
        Objects.requireNonNull(catalogRead, "catalogRead must not be null");
        Objects.requireNonNull(reservation, "reservation must not be null");
    }

    public Policy policyFor(RateLimitPolicy policy) {
        return switch (policy) {
            case CATALOG_READ -> catalogRead;
            case TICKET_RESERVATION -> reservation;
        };
    }

    public record Policy(
            long capacity,
            long refillTokens,
            Duration refillPeriod) {

        public Policy {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            if (refillTokens <= 0) {
                throw new IllegalArgumentException("refillTokens must be positive");
            }
            Objects.requireNonNull(refillPeriod, "refillPeriod must not be null");
            if (refillPeriod.toMillis() <= 0) {
                throw new IllegalArgumentException("refillPeriod must be at least 1ms");
            }
        }
    }
}
