package ru.ticketcraft.gateway.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.gateway.server.webflux.httpclient.pool.type=fixed",
        "spring.cloud.gateway.server.webflux.httpclient.pool.name=pool-test",
        "spring.cloud.gateway.server.webflux.httpclient.pool.max-connections=1",
        "spring.cloud.gateway.server.webflux.httpclient.pool.acquire-timeout=300",
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=30s",
        "ticketcraft.gateway.concurrency.limits.catalog-service-route=4",
        "resilience4j.timelimiter.instances.catalogCircuitBreaker.timeoutDuration=30s",
        "management.tracing.export.otlp.enabled=false" })
@ActiveProfiles("test")
@AutoConfigureWebTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HttpConnectionPoolIntegrationTest {

    private static final String CATALOG_PATH = "/api/v1/catalog/events";
    private static final String RESPONSE_BODY = "Catalog response";

    private static final AtomicInteger DOWNSTREAM_REQUESTS = new AtomicInteger();

    private static final CountDownLatch FIRST_REQUEST_STARTED = new CountDownLatch(1);

    private static final Sinks.Empty<Void> RELEASE_FIRST_REQUEST = Sinks.empty();

    private static final DisposableServer DOWNSTREAM = 
            HttpServer.create()
                      .host("127.0.0.1")
                      .port(0)
                      .handle((request, response) -> {
                          int requestNumber = DOWNSTREAM_REQUESTS.incrementAndGet();

                          if (requestNumber == 1) {
                              FIRST_REQUEST_STARTED.countDown();

                              return RELEASE_FIRST_REQUEST.asMono()
                                                          .then(Mono.defer(() -> response.status(200)
                                                                                         .sendString(Mono.just(RESPONSE_BODY))
                                                                                         .then()));
                          }

                          return response.status(200).sendString(Mono.just(RESPONSE_BODY));
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
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(Mono.just(new Response(true, Map.of())));
    }

    @AfterAll
    static void stopDownstream() {
        RELEASE_FIRST_REQUEST.tryEmitEmpty();
        DOWNSTREAM.disposeNow();
    }

    @Test
    void shouldTimeoutWaitingForConnectionAndRecoverAfterRelease() throws Exception {

        CompletableFuture<Integer> firstResponse = HttpClient.newConnection()
                                                             .baseUrl("http://127.0.0.1:" + gatewayPort)
                                                             .responseTimeout(Duration.ofSeconds(30))
                                                             .get()
                                                             .uri(CATALOG_PATH)
                                                             .responseSingle((response,
                                                                              body) -> body.asString()
                                                                                           .defaultIfEmpty("")
                                                                                           .map(ignored -> response.status()
                                                                                                                   .code()))
                                                             .toFuture();

        try {
            assertTrue(FIRST_REQUEST_STARTED.await(5, TimeUnit.SECONDS), "Первый запрос должен дойти до downstream");

            // Единственное соединение занято первым запросом.
            // Второй должен завершиться по таймауту ожидания пула.
            client.mutate()
                  .responseTimeout(Duration.ofSeconds(5))
                  .build()
                  .get()
                  .uri(CATALOG_PATH)
                  .header("X-Correlation-ID", "pool-timeout-test")
                  .exchange()
                  .expectStatus()
                  .isEqualTo(504)
                  .expectBody()
                  .jsonPath("$.code")
                  .isEqualTo("CATALOG_TIMEOUT")
                  .jsonPath("$.instance")
                  .isEqualTo(CATALOG_PATH)
                  .jsonPath("$.correlationId")
                  .isEqualTo("pool-timeout-test");

            assertEquals(1, DOWNSTREAM_REQUESTS.get(), "Второй запрос не должен дойти до downstream");

            assertFalse(firstResponse.isDone(), "Первый запрос должен продолжать удерживать соединение");
        } finally {
            RELEASE_FIRST_REQUEST.tryEmitEmpty();
        }

        assertEquals(200,
                     firstResponse.get(5, TimeUnit.SECONDS).intValue(),
                     "Первый запрос должен успешно завершиться");

        // После освобождения соединения следующий запрос проходит.
        client.get()
              .uri(CATALOG_PATH)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(String.class)
              .isEqualTo(RESPONSE_BODY);

        assertEquals(2, DOWNSTREAM_REQUESTS.get(), "После освобождения соединения запрос должен дойти до downstream");
    }
}