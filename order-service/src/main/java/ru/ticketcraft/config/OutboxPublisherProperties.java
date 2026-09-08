package ru.ticketcraft.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketcraft.outbox.publisher")
public record OutboxPublisherProperties(
        boolean enabled,
        int batchSize,
        Duration fixedDelay,
        Duration lockDuration,
        Duration retryDelay,
        Duration sendTimeout,
        String topic
) {
}