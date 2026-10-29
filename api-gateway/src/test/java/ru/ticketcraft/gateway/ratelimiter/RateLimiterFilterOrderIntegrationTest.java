package ru.ticketcraft.gateway.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class RateLimiterFilterOrderIntegrationTest {

    private static final String ROUTE_ID = "catalog-service-route";
    private static final String CIRCUIT_BREAKER = "catalogCircuitBreaker";

    private static final AtomicInteger downstreamRequests = new AtomicInteger();

    private static final DisposableServer downstream = HttpServer.create()
                                                                 .host("127.0.0.1")
                                                                 .port(0)
                                                                 .handle((request, response) -> {
                                                                     downstreamRequests.incrementAndGet();

                                                                     return response.status(200)
                                                                                    .sendString(Mono.just("Catalog response"));
                                                                 })
                                                                 .bindNow();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoSpyBean
    private ResilientRateLimiter rateLimiter;

    // Исключает обращение к Keycloak при создании JWT decoder.
    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "ticketcraft.gateway.catalog-uri",
                () -> "http://127.0.0.1:" + downstream.port());
    }

    @AfterAll
    static void stopDownstream() {
        downstream.disposeNow();
    }

    @Test
    void shouldRejectRequestBeforeCircuitBreakerAndDownstream() {
        doReturn(Mono.just(new Response(false, Map.of("X-RateLimit-Remaining", "0")))).when(rateLimiter)
                                                                                      .isAllowed(eq(ROUTE_ID),
                                                                                                 anyString());

        var circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);

        int bufferedCallsBefore = circuitBreaker.getMetrics().getNumberOfBufferedCalls();

        long rejectedCallsBefore = circuitBreaker.getMetrics().getNumberOfNotPermittedCalls();

        int downstreamRequestsBefore = downstreamRequests.get();

        webTestClient.get().uri("/api/v1/catalog/events").exchange().expectStatus().isEqualTo(429);

        verify(rateLimiter).isAllowed(eq(ROUTE_ID), anyString());

        assertEquals(downstreamRequestsBefore,
                     downstreamRequests.get(),
                     "Rate-limited request must not reach downstream");

        assertEquals(bufferedCallsBefore,
                     circuitBreaker.getMetrics().getNumberOfBufferedCalls(),
                     "Rate-limited request must not be recorded by Circuit Breaker");

        assertEquals(rejectedCallsBefore,
                     circuitBreaker.getMetrics().getNumberOfNotPermittedCalls(),
                     "Rate-limited request must not be rejected by Circuit Breaker");
    }
}