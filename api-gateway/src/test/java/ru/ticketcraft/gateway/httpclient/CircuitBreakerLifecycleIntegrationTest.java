package ru.ticketcraft.gateway.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.StateTransition;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import ru.ticketcraft.gateway.ratelimiter.ResilientRateLimiter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {
                    "resilience4j.circuitbreaker.instances.catalogCircuitBreaker.slidingWindowType=COUNT_BASED",
                    "resilience4j.circuitbreaker.instances.catalogCircuitBreaker.slidingWindowSize=2",
                    "resilience4j.circuitbreaker.instances.catalogCircuitBreaker.minimumNumberOfCalls=2",
                    "resilience4j.circuitbreaker.instances.catalogCircuitBreaker.failureRateThreshold=100",
                    "resilience4j.circuitbreaker.instances.catalogCircuitBreaker.waitDurationInOpenState=2s",
                    "resilience4j.circuitbreaker.instances.catalogCircuitBreaker.permittedNumberOfCallsInHalfOpenState=2",
                    "resilience4j.circuitbreaker.instances.catalogCircuitBreaker.automaticTransitionFromOpenToHalfOpenEnabled=false",
                    "resilience4j.circuitbreaker.instances.catalogCircuitBreaker.slowCallDurationThreshold=60s",
                    "resilience4j.timelimiter.instances.catalogCircuitBreaker.timeoutDuration=10s"
                })
@ActiveProfiles("test")
@AutoConfigureWebTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CircuitBreakerLifecycleIntegrationTest {

    private static final String CATALOG_PATH = "/api/v1/catalog/events";
    private static final String BREAKER_NAME = "catalogCircuitBreaker";
    private static final String RESPONSE_BODY = "Catalog response";

    private static final AtomicInteger DOWNSTREAM_STATUS = new AtomicInteger(200);
    private static final AtomicInteger DOWNSTREAM_REQUESTS = new AtomicInteger();

    private static final DisposableServer DOWNSTREAM = 
            HttpServer.create()
                      .host("127.0.0.1")
                      .port(0)
                      .handle((request, response) -> {
                          DOWNSTREAM_REQUESTS.incrementAndGet();
                          return response.status(DOWNSTREAM_STATUS.get()).sendString(Mono.just(RESPONSE_BODY));
                      })
                      .bindNow();

    @Autowired
    private WebTestClient client;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

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
    void shouldOpenProbeRecoverAndReopenAfterFailedProbes() {
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(Mono.just(new Response(true, Map.of())));

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(BREAKER_NAME);

        breaker.reset();
        DOWNSTREAM_REQUESTS.set(0);

        List<StateTransition> transitions = new CopyOnWriteArrayList<>();

        breaker.getEventPublisher().onStateTransition(event -> transitions.add(event.getStateTransition()));

        assertEquals(State.CLOSED, breaker.getState());

        // 1. Два отказа downstream открывают Circuit Breaker.
        DOWNSTREAM_STATUS.set(500);

        assertUnavailable();
        assertEquals(State.CLOSED, breaker.getState());

        assertUnavailable();
        assertEquals(State.OPEN, breaker.getState());
        assertEquals(2, DOWNSTREAM_REQUESTS.get());

        // 2. Даже исправный downstream недоступен через открытый breaker.
        DOWNSTREAM_STATUS.set(200);
        assertBlockedWithoutDownstreamCall(breaker);

        // 3. После ожидания первый пробный запрос переводит в HALF_OPEN.
        awaitOpenInterval();

        assertSuccess();
        assertEquals(State.HALF_OPEN, breaker.getState());
        assertEquals(3, DOWNSTREAM_REQUESTS.get());

        // Второй успешный пробный запрос закрывает breaker.
        assertSuccess();
        assertEquals(State.CLOSED, breaker.getState());
        assertEquals(4, DOWNSTREAM_REQUESTS.get());

        // 4. Повторная серия ошибок снова открывает breaker.
        DOWNSTREAM_STATUS.set(500);

        assertUnavailable();
        assertEquals(State.CLOSED, breaker.getState());

        assertUnavailable();
        assertEquals(State.OPEN, breaker.getState());
        assertEquals(6, DOWNSTREAM_REQUESTS.get());

        assertBlockedWithoutDownstreamCall(breaker);

        // 5. Неуспешные пробные запросы возвращают HALF_OPEN в OPEN.
        awaitOpenInterval();

        assertUnavailable();
        assertEquals(State.HALF_OPEN, breaker.getState());
        assertEquals(7, DOWNSTREAM_REQUESTS.get());

        assertUnavailable();
        assertEquals(State.OPEN, breaker.getState());
        assertEquals(8, DOWNSTREAM_REQUESTS.get());

        // 6. После восстановления сервис снова становится доступен.
        DOWNSTREAM_STATUS.set(200);
        assertBlockedWithoutDownstreamCall(breaker);

        awaitOpenInterval();

        assertSuccess();
        assertEquals(State.HALF_OPEN, breaker.getState());

        assertSuccess();
        assertEquals(State.CLOSED, breaker.getState());
        assertEquals(10, DOWNSTREAM_REQUESTS.get());

        assertEquals(List.of(StateTransition.CLOSED_TO_OPEN,
                             StateTransition.OPEN_TO_HALF_OPEN,
                             StateTransition.HALF_OPEN_TO_CLOSED,
                             StateTransition.CLOSED_TO_OPEN,
                             StateTransition.OPEN_TO_HALF_OPEN,
                             StateTransition.HALF_OPEN_TO_OPEN,
                             StateTransition.OPEN_TO_HALF_OPEN,
                             StateTransition.HALF_OPEN_TO_CLOSED),
                     transitions);
    }

    private void assertSuccess() {
        client.get()
              .uri(CATALOG_PATH)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(String.class)
              .isEqualTo(RESPONSE_BODY);
    }

    private void assertUnavailable() {
        client.get()
              .uri(CATALOG_PATH)
              .header("X-Correlation-ID", "breaker-lifecycle-test")
              .exchange()
              .expectStatus()
              .isEqualTo(503)
              .expectHeader()
              .valueEquals("X-Correlation-ID", "breaker-lifecycle-test")
              .expectBody()
              .jsonPath("$.code")
              .isEqualTo("CATALOG_UNAVAILABLE")
              .jsonPath("$.instance")
              .isEqualTo(CATALOG_PATH)
              .jsonPath("$.correlationId")
              .isEqualTo("breaker-lifecycle-test");
    }

    private void assertBlockedWithoutDownstreamCall(CircuitBreaker breaker) {
        int requestsBefore = DOWNSTREAM_REQUESTS.get();

        assertUnavailable();
        assertEquals(State.OPEN, breaker.getState());
        assertEquals(requestsBefore, DOWNSTREAM_REQUESTS.get(), "В OPEN запрос не должен доходить до downstream");
    }

    private void awaitOpenInterval() {
        // Реальное ожидание: внутренние часы Resilience4j не виртуализированы.
        // После 2 секунд OPEN следующий запрос может стать пробным.
        StepVerifier.create(Mono.delay(Duration.ofMillis(2200)))
                    .expectNext(0L)
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
    }
}