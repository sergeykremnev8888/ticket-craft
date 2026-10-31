package ru.ticketcraft.gateway.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import ru.ticketcraft.gateway.ratelimiter.ResilientRateLimiter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, 
                properties = {
                    "spring.cloud.gateway.server.webflux.httpclient.pool.type=fixed",
                    "spring.cloud.gateway.server.webflux.httpclient.pool.name=cancellation-test",
                    "spring.cloud.gateway.server.webflux.httpclient.pool.max-connections=1",
                    "spring.cloud.gateway.server.webflux.httpclient.pool.acquire-timeout=500",
                    "spring.cloud.gateway.server.webflux.httpclient.pool.metrics=true",
                    "spring.cloud.gateway.server.webflux.httpclient.response-timeout=30s",
                    "ticketcraft.gateway.concurrency.limits.catalog-service-route=1",
                    "resilience4j.timelimiter.instances.catalogCircuitBreaker.timeoutDuration=30s"
                })
@ActiveProfiles("test")
@AutoConfigureWebTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ClientCancellationIntegrationTest {

    private static final String CATALOG_PATH = "/api/v1/catalog/events";
    private static final String ROUTE_ID = "catalog-service-route";
    private static final String POOL_NAME = "cancellation-test";
    private static final String RESPONSE_BODY = "Catalog response";

    private static final AtomicInteger DOWNSTREAM_REQUESTS = new AtomicInteger();
    private static final CountDownLatch FIRST_REQUEST_STARTED = new CountDownLatch(1);
    private static final CountDownLatch FIRST_REQUEST_CANCELLED = new CountDownLatch(1);

    private static final DisposableServer DOWNSTREAM = 
            HttpServer.create()
                      .host("127.0.0.1")
                      .port(0)
                      .handle((request, response) -> {
                          int requestNumber = DOWNSTREAM_REQUESTS.incrementAndGet();
                          if (requestNumber == 1) {
                              return Mono.<Void>never()
                                         .doOnSubscribe(ignored -> FIRST_REQUEST_STARTED.countDown())
                                         .doOnCancel(FIRST_REQUEST_CANCELLED::countDown);
                          }
                          return response.status(200).sendString(Mono.just(RESPONSE_BODY));
                      })
                      .bindNow();

    @Value("${local.server.port}")
    private int gatewayPort;

    @Autowired
    private WebTestClient client;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private ResilientRateLimiter rateLimiter;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("ticketcraft.gateway.catalog-uri", () -> "http://127.0.0.1:" + DOWNSTREAM.port());
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.disposeNow();
    }

    @Test
    void shouldReleaseResourcesWhenClientCancelsRequest() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(Mono.just(new Response(true, Map.of())));

        CompletableFuture<Integer> firstResponse = 
                HttpClient.newConnection()
                          .baseUrl("http://127.0.0.1:" + gatewayPort)
                          .responseTimeout(Duration.ofSeconds(30))
                          .get()
                          .uri(CATALOG_PATH)
                          .responseSingle((response, body) -> body.asString()
                                                                  .defaultIfEmpty("")
                                                                  .map(ignored -> response.status().code()))
                          .toFuture();

        try {
            assertTrue(FIRST_REQUEST_STARTED.await(5, TimeUnit.SECONDS), "The first request must be executed in downstream");
            assertFalse(firstResponse.isDone(), "The first request must be in process");
            assertEquals(1.0, activeRequests(), 0.0);
            assertTrue(poolGaugeEquals("reactor.netty.connection.provider.active.connections", 1.0),
                       "The first request must hold the pool connection");

            // Отмена подписки клиента закрывает его HTTP-соединение к Gateway.
            assertTrue(firstResponse.cancel(true), "The request must be canceled before a response is received");

            assertTrue(FIRST_REQUEST_CANCELLED.await(5, TimeUnit.SECONDS),
                       "The cancellation should propagate downstream.");

            awaitReleasedResources();

            // Проверяем не только метрики, но и реальную доступность ресурсов.
            client.get()
                  .uri(CATALOG_PATH)
                  .exchange()
                  .expectStatus()
                  .isOk()
                  .expectBody(String.class)
                  .isEqualTo(RESPONSE_BODY);

            assertEquals(2, DOWNSTREAM_REQUESTS.get(), "The next request should reach the downstream.");

            awaitReleasedResources();
        } finally {
            firstResponse.cancel(true);
        }
    }

    private double activeRequests() {
        return meterRegistry.get("ticketcraft.gateway.concurrency.active").tag("route", ROUTE_ID).gauge().value();
    }

    private boolean poolGaugeEquals(String metricName, double expected) {
        Collection<Gauge> gauges = meterRegistry.find(metricName).tag("name", POOL_NAME).gauges();

        return !gauges.isEmpty() && gauges.stream().allMatch(gauge -> gauge.value() == expected);
    }

    private void awaitReleasedResources() {
        // Освобождение сетевых ресурсов асинхронно.
        // Проверяем состояние с интервалом, не блокируя Netty-потоки.
        Mono<Boolean> released = 
                Mono.defer(() -> Mono.delay(Duration.ofMillis(50))
                                     .map(ignored -> activeRequests() == 0.0
                                             && poolGaugeEquals("reactor.netty.connection.provider.active.connections",
                                                                0.0)
                                             && poolGaugeEquals("reactor.netty.connection.provider.pending.connections",
                                                                0.0)))
                    .repeat()
                    .filter(Boolean::booleanValue)
                    .next()
                    .timeout(Duration.ofSeconds(5));

        StepVerifier.create(released).expectNext(true).verifyComplete();
    }
}