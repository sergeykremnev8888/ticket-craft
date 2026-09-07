package ru.ticketcraft.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "catalog.service")
public record CatalogProperties(String url) {
}
