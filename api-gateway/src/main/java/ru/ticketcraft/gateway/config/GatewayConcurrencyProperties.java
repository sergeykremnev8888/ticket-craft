package ru.ticketcraft.gateway.config;

import java.util.Map;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Максимальное число активных запросов на маршрут.
 */
@ConfigurationProperties(prefix = "ticketcraft.gateway.concurrency")
public record GatewayConcurrencyProperties(Map<String, Integer> limits) {

    public GatewayConcurrencyProperties {
        limits = Map.copyOf(Objects.requireNonNull(limits, "limits is required"));
        if (limits.isEmpty()) {
            throw new IllegalArgumentException("limits must not be empty");
        }

        limits.forEach((routeId, limit) -> {
            if (routeId.isBlank()) {
                throw new IllegalArgumentException("routeId must not be blank");
            }

            if (limit < 1) {
                throw new IllegalArgumentException("Concurrency limit must be positive: " + routeId);
            }
        });
    }
}