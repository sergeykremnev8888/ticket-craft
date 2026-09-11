package ru.ticketcraft.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "catalog.cache")
public record CatalogCacheProperties(Duration eventsTtl) {

    public CatalogCacheProperties {
        if (eventsTtl == null || eventsTtl.isZero() || eventsTtl.isNegative()) {

            throw new IllegalArgumentException("catalog.cache.events-ttl must be positive");
        }
    }
}