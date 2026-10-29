package ru.ticketcraft.gateway.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
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
import ru.ticketcraft.gateway.ratelimiter.ResilientRateLimiter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class GatewayFallbackIntegrationTest {

    private static final String CATALOG_PATH = "/api/v1/catalog/events";
    private static final String ORDER_PATH = "/api/v1/orders";
    private static final String CORRELATION_ID = "fallback-test-123";

    private static final AtomicInteger DOWNSTREAM_REQUESTS = new AtomicInteger();

    private static final AtomicInteger DOWNSTREAM_STATUS = new AtomicInteger(200);

    private static final AtomicLong DOWNSTREAM_DELAY_MS = new AtomicLong();

    private static final DisposableServer DOWNSTREAM = 
            HttpServer.create()
                      .host("127.0.0.1")
                      .port(0)
                      .handle((request, response) -> {
                          DOWNSTREAM_REQUESTS.incrementAndGet();

                          int status = DOWNSTREAM_STATUS.get();
                          long delayMs = DOWNSTREAM_DELAY_MS.get();

                          // Задерживаем отправку и заголовков, и тела.
                          return Mono.delay(Duration.ofMillis(delayMs))
                                     .then(Mono.defer(() -> response.status(status)
                                                                    .sendString(Mono.just("Original downstream body"))
                                                                    .then()));
                      })
                      .bindNow();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoSpyBean
    private ResilientRateLimiter rateLimiter;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("ticketcraft.gateway.catalog-uri", () -> "http://127.0.0.1:" + DOWNSTREAM.port());
        registry.add("ticketcraft.gateway.order-uri", () -> "http://127.0.0.1:" + DOWNSTREAM.port());
        registry.add("resilience4j.timelimiter.instances.catalogCircuitBreaker.timeoutDuration", () -> "500ms");
        registry.add("resilience4j.timelimiter.instances.orderCircuitBreaker.timeoutDuration", () -> "500ms");
    }

    @BeforeEach
    void setUp() {
        DOWNSTREAM_REQUESTS.set(0);
        DOWNSTREAM_STATUS.set(200);
        DOWNSTREAM_DELAY_MS.set(0);

        circuitBreakerRegistry.circuitBreaker("catalogCircuitBreaker").reset();
        circuitBreakerRegistry.circuitBreaker("orderCircuitBreaker").reset();

        doReturn(Mono.just(new Response(true, Map.of()))).when(rateLimiter).isAllowed(anyString(), anyString());
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.disposeNow();
    }

    @ParameterizedTest
    @ValueSource(ints = { 500, 502, 503 })
    void shouldReturn503WhenDownstreamReturnsServerError(int downstreamStatus) {
        DOWNSTREAM_STATUS.set(downstreamStatus);

        WebTestClient.ResponseSpec response = webTestClient.get()
                                                           .uri(CATALOG_PATH)
                                                           .header("X-Correlation-ID", CORRELATION_ID)
                                                           .exchange();

        assertProblem(response, 503, "CATALOG_UNAVAILABLE", CATALOG_PATH);

        assertEquals(1, DOWNSTREAM_REQUESTS.get());

        assertEquals(1,
                     circuitBreakerRegistry.circuitBreaker("catalogCircuitBreaker")
                                           .getMetrics()
                                           .getNumberOfFailedCalls());
    }

    @Test
    void shouldPreserveTimeoutMeaningWhenDownstreamReturns504() {
        DOWNSTREAM_STATUS.set(504);

        WebTestClient.ResponseSpec response = webTestClient.get()
                                                           .uri(CATALOG_PATH)
                                                           .header("X-Correlation-ID", CORRELATION_ID)
                                                           .exchange();

        assertProblem(response, 504, "CATALOG_TIMEOUT", CATALOG_PATH);

        assertEquals(1, DOWNSTREAM_REQUESTS.get());

        assertEquals(1,
                     circuitBreakerRegistry.circuitBreaker("catalogCircuitBreaker")
                                           .getMetrics()
                                           .getNumberOfFailedCalls());
    }

    @ParameterizedTest
    @ValueSource(ints = { 400, 403, 404, 409, 429 })
    void shouldPassClientErrorsThroughWithoutRecordingCircuitBreakerFailure(int downstreamStatus) {
        DOWNSTREAM_STATUS.set(downstreamStatus);

        webTestClient.get()
                     .uri(CATALOG_PATH)
                     .header("X-Correlation-ID", CORRELATION_ID)
                     .exchange()
                     .expectStatus()
                     .isEqualTo(downstreamStatus)
                     .expectBody(String.class)
                     .isEqualTo("Original downstream body");

        assertEquals(1, DOWNSTREAM_REQUESTS.get());

        assertEquals(0,
                     circuitBreakerRegistry.circuitBreaker("catalogCircuitBreaker")
                                           .getMetrics()
                                           .getNumberOfFailedCalls());
    }

    @Test
    void shouldReturn504WithOriginalPathWhenCatalogTimesOut() {
        DOWNSTREAM_DELAY_MS.set(5_000);

        WebTestClient.ResponseSpec response = webTestClient.get()
                                                           .uri(CATALOG_PATH)
                                                           .header("X-Correlation-ID", CORRELATION_ID)
                                                           .exchange();

        assertProblem(response, 504, "CATALOG_TIMEOUT", CATALOG_PATH);
    }

    @Test
    void shouldPreserveOriginalPathWhenCircuitBreakerIsOpen() {
        circuitBreakerRegistry.circuitBreaker("catalogCircuitBreaker").transitionToOpenState();

        WebTestClient.ResponseSpec response = webTestClient.get()
                                                           .uri(CATALOG_PATH)
                                                           .header("X-Correlation-ID", CORRELATION_ID)
                                                           .exchange();

        assertProblem(response, 503, "CATALOG_UNAVAILABLE", CATALOG_PATH);

        assertEquals(0, DOWNSTREAM_REQUESTS.get());
    }

    @Test
    void shouldHandleOrderPostTimeoutWithoutRetry() {
        DOWNSTREAM_DELAY_MS.set(5_000);

        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue("valid-token")
                     .header("alg", "RS256")
                     .subject("user-123")
                     .issuedAt(now)
                     .expiresAt(now.plusSeconds(300))
                     .build();

        when(jwtDecoder.decode("valid-token")).thenReturn(Mono.just(jwt));

        WebTestClient.ResponseSpec response = webTestClient.post()
                                                           .uri(ORDER_PATH)
                                                           .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                                                           .header("X-Correlation-ID", CORRELATION_ID)
                                                           .header("Idempotency-Key", "order-test-123")
                                                           .contentType(MediaType.APPLICATION_JSON)
                                                           .bodyValue(Map.of("ticketId", "test-ticket"))
                                                           .exchange();

        assertProblem(response, 504, "ORDER_SERVICE_TIMEOUT", ORDER_PATH).jsonPath("$.detail")
                                                                         .value((String detail) -> {
                                                                             assertTrue(detail.contains("operation may still have completed"));
                                                                             assertTrue(detail.contains("same Idempotency-Key"));
                                                                         });

        assertEquals(1, DOWNSTREAM_REQUESTS.get());
    }

    private WebTestClient.BodyContentSpec assertProblem(WebTestClient.ResponseSpec response,
                                                        int status,
                                                        String code,
                                                        String originalPath) {

        return response.expectStatus()
                       .isEqualTo(status)
                       .expectHeader()
                       .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                       .expectHeader()
                       .valueEquals("X-Correlation-ID", CORRELATION_ID)
                       .expectBody()
                       .jsonPath("$.type").isEqualTo("about:blank")
                       .jsonPath("$.status").isEqualTo(status)
                       .jsonPath("$.code").isEqualTo(code)
                       .jsonPath("$.instance").isEqualTo(originalPath)
                       .jsonPath("$.correlationId").isEqualTo(CORRELATION_ID)
                       .jsonPath("$.properties").doesNotExist();
    }
}