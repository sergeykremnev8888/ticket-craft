package ru.ticketcraft.config;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CatalogCacheConfiguration implements CachingConfigurer {

    private static final String CACHE_PREFIX = "ticketcraft:catalog:";

    private final CatalogCacheProperties properties;

    public CatalogCacheConfiguration(CatalogCacheProperties properties) {

        this.properties = properties;
    }

    @Bean
    RedisCacheManagerBuilderCustomizer catalogRedisCacheManagerBuilderCustomizer() {

        RedisSerializer<Object> valueSerializer = RedisSerializer.json();

        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.eventsTtl()).disableCachingNullValues().prefixCacheNameWith(CACHE_PREFIX)
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer));

        return builder -> builder.withCacheConfiguration(CatalogCacheNames.EVENTS, configuration)
                .withCacheConfiguration(CatalogCacheNames.EVENT_BY_ID, configuration).disableCreateOnMissingCache()
                .enableStatistics();
    }

    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}