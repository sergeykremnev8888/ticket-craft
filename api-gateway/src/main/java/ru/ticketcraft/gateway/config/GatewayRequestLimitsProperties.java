package ru.ticketcraft.gateway.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Настройки ограничения тела HTTP-запросов.
 */
@ConfigurationProperties(prefix = "ticketcraft.gateway.request-limits")
public record GatewayRequestLimitsProperties(DataSize maxBodySize, Duration bodyReadTimeout) {

    public GatewayRequestLimitsProperties {
        Objects.requireNonNull(maxBodySize, "maxBodySize is required");
        Objects.requireNonNull(bodyReadTimeout, "bodyReadTimeout is required");

        long bytes = maxBodySize.toBytes();
        if (bytes < 1 || bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBodySize must be between 1 and Integer.MAX_VALUE bytes");
        }

        if (bodyReadTimeout.isZero() || bodyReadTimeout.isNegative()) {
            throw new IllegalArgumentException("bodyReadTimeout must be positive");
        }
    }

    public int maxBodyBytes() {
        return Math.toIntExact(maxBodySize.toBytes());
    }
}