package ru.ticketcraft.gateway.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки обращения к endpoint публичных ключей.
 */
@ConfigurationProperties(prefix = "ticketcraft.gateway.jwk")
public record GatewayJwkProperties(Duration requestTimeout) {

    public GatewayJwkProperties {
        Objects.requireNonNull(requestTimeout, "requestTimeout is required");

        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }
}