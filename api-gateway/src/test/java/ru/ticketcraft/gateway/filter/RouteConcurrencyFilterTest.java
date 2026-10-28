package ru.ticketcraft.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.ticketcraft.gateway.config.GatewayConcurrencyProperties;
import ru.ticketcraft.gateway.exception.GatewayProblemWriter;

class RouteConcurrencyFilterTest {

    private GatewayProblemWriter writer;
    private RouteConcurrencyFilter filter;

    @BeforeEach
    void setUp() {
        writer = mock(GatewayProblemWriter.class);

        when(writer.write(any(), any(), anyString(), anyString())).thenReturn(Mono.empty());

        GatewayConcurrencyProperties properties = 
                new GatewayConcurrencyProperties(Map.of("catalog", 1, "order", 1));
        filter = new RouteConcurrencyFilter(properties, writer);
    }

    @Test
    void shouldRejectExcessRequestAndReleasePermitAfterCancellation() {
        MockServerWebExchange rejected = exchange("catalog");
        AtomicInteger downstreamCalls = new AtomicInteger();

        Mono<Void> pending = filter.filter(exchange("catalog"), ignored -> Mono.never());

        StepVerifier.create(pending).then(() -> {
            StepVerifier.create(filter.filter(rejected, ignored -> {
                downstreamCalls.incrementAndGet();
                return Mono.empty();
            })).verifyComplete();

            assertEquals(0, downstreamCalls.get());

            verify(writer).write(eq(rejected),
                                 eq(HttpStatus.SERVICE_UNAVAILABLE),
                                 eq("GATEWAY_OVERLOADED"),
                                 anyString());

            assertEquals("1", rejected.getResponse().getHeaders().getFirst("Retry-After"));
        }).thenCancel().verify();

        // После отмены первого запроса разрешение снова доступно.
        assertRequestAllowed("catalog");
    }

    @Test
    void shouldReleasePermitAfterCompletion() {
        assertRequestAllowed("catalog");
        assertRequestAllowed("catalog");
    }

    @Test
    void shouldReleasePermitAfterError() {
        StepVerifier.create(filter.filter(exchange("catalog"),
                                          ignored -> Mono.error(new IllegalStateException("Downstream failed"))))
                    .expectError(IllegalStateException.class)
                    .verify();

        assertRequestAllowed("catalog");
    }

    @Test
    void shouldReleasePermitAfterSynchronousFailure() {
        StepVerifier.create(filter.filter(exchange("catalog"), ignored -> {
            throw new IllegalStateException("Synchronous failure");
        })).expectError(IllegalStateException.class).verify();

        assertRequestAllowed("catalog");
    }

    @Test
    void shouldUseIndependentLimitsForDifferentRoutes() {
        Mono<Void> pending = filter.filter(exchange("catalog"), ignored -> Mono.never());

        StepVerifier.create(pending).then(() -> assertRequestAllowed("order")).thenCancel().verify();
    }

    @Test
    void shouldAcquirePermitOnlyOnSubscription() {
        // Создание Mono без подписки не должно занимать разрешение.
        filter.filter(exchange("catalog"), ignored -> Mono.never());

        assertRequestAllowed("catalog");
    }

    private void assertRequestAllowed(String routeId) {
        AtomicInteger downstreamCalls = new AtomicInteger();

        StepVerifier.create(filter.filter(exchange(routeId), ignored -> {
            downstreamCalls.incrementAndGet();
            return Mono.empty();
        })).verifyComplete();

        assertEquals(1, downstreamCalls.get());
    }

    private MockServerWebExchange exchange(String routeId) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/catalog/events"));

        Route route = Route.async()
                           .id(routeId)
                           .uri("http://localhost:8081")
                           .asyncPredicate(ignored -> Mono.just(true))
                           .build();

        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);

        return exchange;
    }
}