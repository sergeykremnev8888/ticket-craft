package ru.ticketcraft.catalog.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "catalog.reservation")
public record ReservationProperties(Duration duration) {
}
