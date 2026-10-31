package ru.ticketcraft.gateway.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.PrematureCloseException;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import ru.ticketcraft.gateway.ratelimiter.ResilientRateLimiter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {
                    "spring.cloud.gateway.server.webflux.httpclient.pool.type=fixed",
                    "spring.cloud.gateway.server.webflux.httpclient.pool.name=partial-response-test",
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
class PartialResponseFailureIntegrationTest {

    private static final String CATALOG_PATH = "/api/v1/catalog/events";
    private static final String ROUTE_ID = "catalog-service-route";
    private static final String POOL_NAME = "partial-response-test";

    private static final String PARTIAL_BODY = "{\"events\":[";
    private static final String COMPLETE_BODY = "{\"events\":[]}";

    private static final AtomicInteger DOWNSTREAM_REQUESTS = new AtomicInteger();
    private static final AtomicReference<Connection> FIRST_CONNECTION = new AtomicReference<>();

    private static final DisposableServer DOWNSTREAM = 
            HttpServer.create()
                      .host("127.0.0.1")
                      .port(0)
                      .handle((request, response) -> {
                          int requestNumber = DOWNSTREAM_REQUESTS.incrementAndGet();

                          if (requestNumber == 1) {
                              request.withConnection(FIRST_CONNECTION::set);

                              response.status(200)
                                      .header(HttpHeaders.CONTENT_TYPE, "application/json")
                                      .header(HttpHeaders.CONTENT_LENGTH, "100");

                              // Первая группа отправляется и flush-ится.
                              // Следующая не завершается: соединение закроет
                              // тест.
                              return response.sendGroups(Flux.concat(Mono.just(Mono.fromSupplier(() -> Unpooled.copiedBuffer(PARTIAL_BODY,
                                                                                                                             StandardCharsets.UTF_8))),
                                                                     Flux.<Mono<ByteBuf>>never()));
                          }

                          return response.status(200)
                                         .header(HttpHeaders.CONTENT_TYPE, "application/json")
                                         .sendString(Mono.just(COMPLETE_BODY));
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
        Connection connection = FIRST_CONNECTION.get();
        if (connection != null) {
            connection.dispose();
        }

        DOWNSTREAM.disposeNow();
    }

    @Test
    void shouldAbortPartialResponseAndReleaseResources() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(Mono.just(new Response(true, Map.of())));

        CountDownLatch firstChunkReceived = new CountDownLatch(1);
        AtomicInteger receivedStatus = new AtomicInteger();
        AtomicReference<String> receivedContentType = new AtomicReference<>();
        StringBuffer receivedBody = new StringBuffer();

        CompletableFuture<Void> firstResponse = 
                HttpClient.newConnection()
                          .baseUrl("http://127.0.0.1:" + gatewayPort)
                          .responseTimeout(Duration.ofSeconds(30))
                          .get()
                          .uri(CATALOG_PATH)
                          .response((response, body) -> {
                              receivedStatus.set(response.status().code());
                              receivedContentType.set(response.responseHeaders().get(HttpHeaders.CONTENT_TYPE));

                              return body.asString(StandardCharsets.UTF_8).doOnNext(chunk -> {
                                  receivedBody.append(chunk);
                                  firstChunkReceived.countDown();
                              });
                          })
                          .then()
                          .toFuture();

        try {
            assertTrue(firstChunkReceived.await(5, TimeUnit.SECONDS), "Клиент должен получить часть тела до обрыва");

            assertEquals(200, receivedStatus.get());
            assertEquals("application/json", receivedContentType.get());

            Connection connection = FIRST_CONNECTION.get();
            assertNotNull(connection);

            // Обрываем downstream после фактической отправки части ответа.
            connection.dispose();

            ExecutionException exception = assertThrows(ExecutionException.class,
                                                        () -> firstResponse.get(5, TimeUnit.SECONDS));

            assertTrue(hasPrematureCloseCause(exception),
                       () -> "Ожидалась ошибка незавершённого HTTP-ответа: " + exception);

            // Gateway не должен дописывать Problem Detail в начатое тело.
            assertEquals(PARTIAL_BODY, receivedBody.toString());

            awaitReleasedResources();

            client.get()
                  .uri(CATALOG_PATH)
                  .exchange()
                  .expectStatus()
                  .isOk()
                  .expectBody(String.class)
                  .isEqualTo(COMPLETE_BODY);

            assertEquals(2, DOWNSTREAM_REQUESTS.get(), "Следующий запрос должен успешно дойти до downstream");

            awaitReleasedResources();
        } finally {
            firstResponse.cancel(true);

            Connection connection = FIRST_CONNECTION.get();
            if (connection != null) {
                connection.dispose();
            }
        }
    }

    private boolean hasPrematureCloseCause(Throwable exception) {
        Throwable current = exception;

        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof PrematureCloseException) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    private double activeRequests() {
        return meterRegistry.get("ticketcraft.gateway.concurrency.active").tag("route", ROUTE_ID).gauge().value();
    }

    private boolean poolGaugeEquals(String metricName, double expected) {
        Collection<Gauge> gauges = meterRegistry.find(metricName).tag("name", POOL_NAME).gauges();

        return !gauges.isEmpty() && gauges.stream().allMatch(gauge -> gauge.value() == expected);
    }

    private void awaitReleasedResources() {
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