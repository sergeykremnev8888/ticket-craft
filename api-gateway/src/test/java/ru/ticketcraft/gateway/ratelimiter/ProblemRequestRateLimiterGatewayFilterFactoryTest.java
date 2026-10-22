package ru.ticketcraft.gateway.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.ticketcraft.gateway.exception.GatewayProblemWriter;

class ProblemRequestRateLimiterGatewayFilterFactoryTest {

    private static final String ROUTE_ID = "order-service-route";
    private static final String CLIENT_KEY = "user:123";

    private RateLimiter<RedisRateLimiter.Config> rateLimiter;
    private KeyResolver keyResolver;
    private GatewayProblemWriter problemWriter;

    private GatewayFilter filter;
    private MockServerWebExchange exchange;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        keyResolver = mock(KeyResolver.class);
        problemWriter = mock(GatewayProblemWriter.class);

        RequestRateLimiterGatewayFilterFactory delegate = 
                new RequestRateLimiterGatewayFilterFactory(rateLimiter, keyResolver);

        ProblemRequestRateLimiterGatewayFilterFactory factory = 
                new ProblemRequestRateLimiterGatewayFilterFactory(delegate, problemWriter);

        RequestRateLimiterGatewayFilterFactory.Config config = new RequestRateLimiterGatewayFilterFactory.Config();
        config.setRouteId(ROUTE_ID);
        config.setDenyEmptyKey(true);
        config.setEmptyKeyStatus("FORBIDDEN");
        config.setThrowOnLimit(false);

        filter = factory.apply(config);

        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders/1"));

        when(keyResolver.resolve(any(ServerWebExchange.class))).thenReturn(Mono.just(CLIENT_KEY));

        when(problemWriter.write(any(ServerWebExchange.class),
                                 any(HttpStatus.class),
                                 anyString(),
                                 anyString())).thenReturn(Mono.empty());
    }

    @Test
    void shouldWriteProblemWhenRateLimitIsExceeded() {
        when(rateLimiter.isAllowed(ROUTE_ID, CLIENT_KEY)).thenReturn(Mono.just(new RateLimiter.Response(false,
                Map.of("X-RateLimit-Remaining", "0"))));

        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(problemWriter).write(eq(exchange),
                                    eq(HttpStatus.TOO_MANY_REQUESTS),
                                    eq("RATE_LIMIT_EXCEEDED"),
                                    anyString());

        assertEquals("0", exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));

        verifyNoInteractions(chain);
    }

    @Test
    void shouldWriteProblemWhenKeyIsMissing() {
        when(keyResolver.resolve(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(problemWriter).write(eq(exchange), eq(HttpStatus.FORBIDDEN), eq("RATE_LIMIT_KEY_MISSING"), anyString());

        verifyNoInteractions(rateLimiter, chain);
    }

    @Test
    void shouldContinueChainWhenRequestIsAllowed() {
        allowRequest();

        AtomicBoolean downstreamCalled = new AtomicBoolean();

        GatewayFilterChain chain = downstreamExchange -> {
            downstreamCalled.set(true);

            downstreamExchange.getResponse().setStatusCode(HttpStatus.OK);
            return downstreamExchange.getResponse().setComplete();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(true, downstreamCalled.get());
        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());

        assertEquals("9", exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));

        verifyNoInteractions(problemWriter);
    }

    @Test
    void shouldNotRewriteDownstream429() {
        assertDownstreamResponseIsPreserved(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void shouldNotRewriteDownstream403() {
        assertDownstreamResponseIsPreserved(HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldNotRewriteDownstream403WithoutBody() {
        allowRequest();

        GatewayFilterChain chain = downstreamExchange -> {
            downstreamExchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);

            return downstreamExchange.getResponse().setComplete();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());

        verifyNoInteractions(problemWriter);
    }

    private void assertDownstreamResponseIsPreserved(HttpStatus status) {
        allowRequest();

        String body = "{\"code\":\"DOWNSTREAM_ERROR\"}";

        GatewayFilterChain chain = downstreamExchange -> {
            downstreamExchange.getResponse().setStatusCode(status);

            return downstreamExchange.getResponse()
                                     .writeWith(Mono.just(downstreamExchange.getResponse()
                                                                            .bufferFactory()
                                                                            .wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(status, exchange.getResponse().getStatusCode());

        StepVerifier.create(exchange.getResponse().getBodyAsString()).expectNext(body).verifyComplete();

        verifyNoInteractions(problemWriter);
    }

    private void allowRequest() {
        when(rateLimiter.isAllowed(ROUTE_ID, CLIENT_KEY))
            .thenReturn(Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "9"))));
    }
}