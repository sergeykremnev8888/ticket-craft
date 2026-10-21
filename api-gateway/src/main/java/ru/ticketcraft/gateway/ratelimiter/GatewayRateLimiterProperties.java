package ru.ticketcraft.gateway.ratelimiter;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketcraft.gateway.rate-limiter")
public record GatewayRateLimiterProperties(Duration timeout, Duration warnInterval) {

    public GatewayRateLimiterProperties {
        requirePositive(timeout, "timeout");
        requirePositive(warnInterval, "warn-interval");
    }

    private static void requirePositive(Duration value, String property) {
        Objects.requireNonNull(value, property + " must be configured");

        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }
}
