package ru.ticketcraft.gateway.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ResilientRateLimiterTest {

    private static final String ROUTE_ID = "order-service-route";
    private static final String CLIENT_ID = "client-123";

    private static final String METRIC = "ticketcraft.gateway.rate.limiter.fail.open";

    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final Duration WARN_INTERVAL = Duration.ofSeconds(30);

    private RateLimiter<RedisRateLimiter.Config> delegate;
    private SimpleMeterRegistry meterRegistry;
    private ResilientRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        delegate = mock(RateLimiter.class);
        meterRegistry = new SimpleMeterRegistry();

        limiter = new ResilientRateLimiter(delegate, TIMEOUT, WARN_INTERVAL, meterRegistry);
    }

    @Test
    void shouldPreserveAllowedResponseWithoutRecordingFailOpen() {
        Response expected = new Response(true, Map.of("X-RateLimit-Remaining", "9"));

        when(delegate.isAllowed(ROUTE_ID, CLIENT_ID)).thenReturn(Mono.just(expected));

        StepVerifier.create(limiter.isAllowed(ROUTE_ID, CLIENT_ID))
                    .assertNext(actual -> assertSame(expected, actual))
                    .verifyComplete();

        assertNoFailOpen();
        verify(delegate).isAllowed(ROUTE_ID, CLIENT_ID);
    }

    @Test
    void shouldPreserveRejectedResponseWithoutRecordingFailOpen() {
        Response expected = new Response(false, Map.of("X-RateLimit-Remaining", "0"));

        when(delegate.isAllowed(ROUTE_ID, CLIENT_ID)).thenReturn(Mono.just(expected));

        StepVerifier.create(limiter.isAllowed(ROUTE_ID, CLIENT_ID))
                    .assertNext(actual -> assertSame(expected, actual))
                    .verifyComplete();

        assertNoFailOpen();
    }

    @Test
    void shouldAllowRequestWhenDelegateReturnsError() {
        when(delegate.isAllowed(ROUTE_ID,
                                CLIENT_ID)).thenReturn(Mono.error(new IllegalStateException("Redis unavailable")));

        StepVerifier.create(limiter.isAllowed(ROUTE_ID, CLIENT_ID)).assertNext(response -> {
            assertTrue(response.isAllowed());
            assertTrue(response.getHeaders().isEmpty());
        }).verifyComplete();

        assertOnlyFailOpen("error", 1);
    }

    @Test
    void shouldAllowRequestWhenDelegateThrowsSynchronously() {
        when(delegate.isAllowed(ROUTE_ID, CLIENT_ID)).thenThrow(new IllegalStateException("Redis unavailable"));

        StepVerifier.create(limiter.isAllowed(ROUTE_ID, CLIENT_ID))
                    .assertNext(response -> assertTrue(response.isAllowed()))
                    .verifyComplete();

        assertOnlyFailOpen("error", 1);
    }

    @Test
    void shouldAllowRequestAfterTimeoutAndCancelDelegate() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();

        when(delegate.isAllowed(ROUTE_ID, CLIENT_ID)).thenReturn(Mono.<Response>never()
                                                                     .doOnCancel(() -> cancelled.set(true)));

        StepVerifier.withVirtualTime(() -> limiter.isAllowed(ROUTE_ID, CLIENT_ID))
                    .expectSubscription()
                    .expectNoEvent(TIMEOUT.minusMillis(1))
                    .thenAwait(Duration.ofMillis(1))
                    .assertNext(response -> {
                        assertTrue(response.isAllowed());
                        assertTrue(response.getHeaders().isEmpty());
                    })
                    .verifyComplete();

        assertTrue(cancelled.get());
        assertOnlyFailOpen("timeout", 1);
    }

    @Test
    void shouldRecordDelegateFailOpenOnceAndPreserveResponse() {
        Response expected = new Response(true, Map.of("X-RateLimit-Remaining", "-1"));

        when(delegate.isAllowed(ROUTE_ID, CLIENT_ID)).thenReturn(Mono.just(expected));

        StepVerifier.create(limiter.isAllowed(ROUTE_ID, CLIENT_ID))
                    .assertNext(actual -> assertSame(expected, actual))
                    .verifyComplete();

        assertOnlyFailOpen("delegate_fail_open", 1);
    }

    @Test
    void shouldCountEveryFailOpenDespiteWarningSuppression() {
        when(delegate.isAllowed(ROUTE_ID,
                                CLIENT_ID)).thenReturn(Mono.error(new IllegalStateException("Redis unavailable")));

        StepVerifier.create(limiter.isAllowed(ROUTE_ID, CLIENT_ID)
                                   .then(limiter.isAllowed(ROUTE_ID, CLIENT_ID))
                                   .then(limiter.isAllowed(ROUTE_ID, CLIENT_ID)))
                    .assertNext(response -> assertTrue(response.isAllowed()))
                    .verifyComplete();

        assertOnlyFailOpen("error", 3);
    }

    private void assertNoFailOpen() {
        assertTrue(meterRegistry.find(METRIC).counters().isEmpty());
    }

    private void assertOnlyFailOpen(String reason, double expectedCount) {
        var counters = meterRegistry.find(METRIC).counters();

        assertEquals(1, counters.size());

        var counter = meterRegistry.get(METRIC).tags("route", ROUTE_ID, "reason", reason).counter();

        assertEquals(expectedCount, counter.count(), 0.0);
    }
}