package ru.ticketcraft.gateway.limiter;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;

import reactor.core.publisher.Mono;

public class ResilientRateLimiter implements RateLimiter<RedisRateLimiter.Config> {

    private static final Logger log = LoggerFactory.getLogger(ResilientRateLimiter.class);

    private final RateLimiter<RedisRateLimiter.Config> redisRateLimiter;

    private final Duration allowedTimeout;

    public ResilientRateLimiter(RedisRateLimiter redisRateLimiter, Duration allowedTimeout) {
        this.redisRateLimiter = Objects.requireNonNull(redisRateLimiter);
        this.allowedTimeout = Objects.requireNonNull(allowedTimeout);
    }

    @Override
    public Map<String, RedisRateLimiter.Config> getConfig() {
        return redisRateLimiter.getConfig();
    }

    @Override
    public Class<RedisRateLimiter.Config> getConfigClass() {
        return redisRateLimiter.getConfigClass();
    }

    @Override
    public RedisRateLimiter.Config newConfig() {
        return redisRateLimiter.newConfig();
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        RedisRateLimiter.Config config = redisRateLimiter.getConfig().get(routeId);

        log.info("RateLimiter route={}, config={}", routeId, config);

        return redisRateLimiter.isAllowed(routeId, id)
                .timeout(allowedTimeout)
                .onErrorResume(this::fallback);
    }

    private Mono<Response> fallback(Throwable ex) {
        log.warn("Rate limiter unavailable: {}. Allowing request", ex.toString());
        return Mono.just(new RateLimiter.Response(true, Map.of()));
    }

}
