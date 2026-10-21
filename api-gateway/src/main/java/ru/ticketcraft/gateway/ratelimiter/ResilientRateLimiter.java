package ru.ticketcraft.gateway.ratelimiter;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;

import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

public class ResilientRateLimiter implements RateLimiter<RedisRateLimiter.Config> {

    private static final Logger log = LoggerFactory.getLogger(ResilientRateLimiter.class);

    private static final String FAIL_OPEN_METRIC = "ticketcraft.gateway.rate.limiter.fail.open";

    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private final RateLimiter<RedisRateLimiter.Config> delegate;
    private final Duration allowedTimeout;
    private final MeterRegistry meterRegistry;

    private final long warnIntervalNanos;
    private final AtomicLong lastWarningNanos;

    public ResilientRateLimiter(RateLimiter<RedisRateLimiter.Config> delegate,
                                Duration allowedTimeout,
                                Duration warnInterval,
                                MeterRegistry meterRegistry) {
        this.delegate = Objects.requireNonNull(delegate, "'delegate' is null");
        this.allowedTimeout = Objects.requireNonNull(allowedTimeout, "'allowedTimeout' is null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "'meterRegistry' is null");

        Objects.requireNonNull(warnInterval, "'warnInterval' is null");

        if (allowedTimeout.isZero() || allowedTimeout.isNegative()) {
            throw new IllegalArgumentException("'allowedTimeout' must be positive");
        }

        if (warnInterval.isZero() || warnInterval.isNegative()) {
            throw new IllegalArgumentException("'warnInterval' must be positive");
        }

        this.warnIntervalNanos = warnInterval.toNanos();
        this.lastWarningNanos = new AtomicLong(System.nanoTime() - warnIntervalNanos);
    }

    @Override
    public Map<String, RedisRateLimiter.Config> getConfig() {
        return delegate.getConfig();
    }

    @Override
    public Class<RedisRateLimiter.Config> getConfigClass() {
        return delegate.getConfigClass();
    }

    @Override
    public RedisRateLimiter.Config newConfig() {
        return delegate.newConfig();
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        Mono<Response> result = Mono.defer(
                () -> delegate.isAllowed(routeId, id));

        result = result.doOnNext(response -> {
            if (isDelegateFailOpen(response)) {
                logFailOpen(routeId, "delegate_fail_open", null);
            }
        });

        return result
                .timeout(
                        allowedTimeout,
                        Mono.defer(() -> allowRequest(routeId, "timeout", null)))
                .onErrorResume(error -> allowRequest(routeId, "error", error));
    }

    /**
     * RedisRateLimiter в Spring Cloud Gateway при ряде ошибок Redis разрешает
     * запрос и возвращает остаток токенов -1.
     *
     * <p>
     * Проверка зависит от стандартного имени заголовка и включённой настройки
     * includeHeaders у делегата.
     */
    private boolean isDelegateFailOpen(Response response) {
        return response.isAllowed() && "-1".equals(response.getHeaders().get(REMAINING_HEADER));
    }

    private Mono<Response> allowRequest(String routeId, String reason, Throwable error) {
        logFailOpen(routeId, reason, error);
        return Mono.just(new Response(true, Map.of()));
    }

    private void logFailOpen(String routeId, String reason, Throwable error) {
        meterRegistry.counter(FAIL_OPEN_METRIC, "route", routeId, "reason", reason).increment();

        long now = System.nanoTime();
        long previous = lastWarningNanos.get();

        if (now - previous >= warnIntervalNanos && lastWarningNanos.compareAndSet(previous, now)) {
            log.warn("Rate limiter degraded: route={}, reason={}, error={}. "
                         + "Allowing requests; further warnings suppressed for {}",
                     routeId, reason, error == null ? "not available" : error.toString(),
                     Duration.ofNanos(warnIntervalNanos));
        }
    }
}
