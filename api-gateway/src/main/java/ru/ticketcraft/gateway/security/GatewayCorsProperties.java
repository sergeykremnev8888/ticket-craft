package ru.ticketcraft.gateway.security;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketcraft.gateway.cors")
public record GatewayCorsProperties(List<String> allowedOrigins,
                                    List<String> allowedMethods,
                                    List<String> allowedHeaders,
                                    List<String> exposedHeaders,
                                    Duration maxAge) {
    public GatewayCorsProperties {
        allowedOrigins = List.copyOf(Objects.requireNonNull(allowedOrigins, "'allowedOrigins' is null"));
        allowedMethods = List.copyOf(Objects.requireNonNull(allowedMethods, "'allowedMethods' is null"));
        allowedHeaders = List.copyOf(Objects.requireNonNull(allowedHeaders, "'allowedHeaders' is null"));
        exposedHeaders = List.copyOf(Objects.requireNonNullElse(exposedHeaders, List.of()));

        if (maxAge == null || maxAge.isNegative()) {
            throw new IllegalArgumentException("ticketcraft.gateway.cors.max-age must be non-negative");
        }
    }
}