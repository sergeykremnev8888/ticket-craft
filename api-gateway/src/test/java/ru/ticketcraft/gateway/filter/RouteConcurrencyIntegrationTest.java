package ru.ticketcraft.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import ru.ticketcraft.gateway.ratelimiter.ResilientRateLimiter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, 
                properties = {
                    "ticketcraft.gateway.concurrency.limits.catalog-service-route=1",
                    "ticketcraft.gateway.request-limits.max-body-size=8B",
                    "resilience4j.timelimiter.instances.catalogCircuitBreaker.timeoutDuration=30s"
                })
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class RouteConcurrencyIntegrationTest {

    private static final String CATALOG_PATH = "/api/v1/catalog/events";

    private static final AtomicInteger DOWNSTREAM_REQUESTS = new AtomicInteger();

    private static final CountDownLatch FIRST_REQUEST_STARTED = new CountDownLatch(1);

    private static final Sinks.Empty<Void> RELEASE_FIRST_REQUEST = Sinks.empty();

    private static final DisposableServer DOWNSTREAM = 
            HttpServer.create()
                      .host("127.0.0.1")
                      .port(0)
                      .handle((request, response) -> {
                          DOWNSTREAM_REQUESTS.incrementAndGet();
                          FIRST_REQUEST_STARTED.countDown();

                          return RELEASE_FIRST_REQUEST.asMono()
                                                      .then(Mono.defer(() -> response.status(200)
                                                                                     .sendString(Mono.just("Catalog response"))
                                                                                     .then()));
                      })
                      .bindNow();

    @Value("${local.server.port}")
    private int gatewayPort;

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private ResilientRateLimiter rateLimiter;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("ticketcraft.gateway.catalog-uri", () -> "http://127.0.0.1:" + DOWNSTREAM.port());
    }

    @BeforeEach
    void setUp() {
        // Проверяем Bulkhead независимо от Redis rate limiting.
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(Mono.just(new Response(true, Map.of())));
    }

    @AfterAll
    static void stopDownstream() {
        RELEASE_FIRST_REQUEST.tryEmitEmpty();
        DOWNSTREAM.disposeNow();
    }

    @Test
    void shouldRejectSecondRequestBeforeReadingItsBody() throws Exception {
        CompletableFuture<Integer> firstResponse = 
                HttpClient.create()
                          .baseUrl("http://127.0.0.1:" + gatewayPort)
                          .responseTimeout(Duration.ofSeconds(30))
                          .get()
                          .uri(CATALOG_PATH)
                          .responseSingle((response, body) -> body.asString().map(ignored -> response.status().code()))
                          .toFuture();

        try {
            assertTrue(FIRST_REQUEST_STARTED.await(5, TimeUnit.SECONDS), "First request must reach downstream");

            // Первый запрос ещё выполняется.
            // Тело второго запроса превышает тестовый лимит 8 байт.
            client.method(HttpMethod.GET)
                  .uri(CATALOG_PATH)
                  .contentType(MediaType.APPLICATION_OCTET_STREAM)
                  .bodyValue("123456789")
                  .exchange()
                  .expectStatus()
                  .isEqualTo(503)
                  .expectHeader()
                  .valueEquals("Retry-After", "1")
                  .expectBody()
                  .jsonPath("$.code")
                  .isEqualTo("GATEWAY_OVERLOADED")
                  .jsonPath("$.instance")
                  .isEqualTo(CATALOG_PATH)
                  .jsonPath("$.correlationId")
                  .isNotEmpty();

            assertEquals(1, DOWNSTREAM_REQUESTS.get(), "Rejected request must not reach downstream");
        } finally {
            RELEASE_FIRST_REQUEST.tryEmitEmpty();
        }

        assertEquals(200,
                     firstResponse.get(5, TimeUnit.SECONDS).intValue(),
                     "First request must complete successfully");
    }
}