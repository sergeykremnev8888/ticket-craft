package ru.ticketcraft.gateway.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class RedisRateLimiterIntegrationTest {

    private static final String ROUTE_ID = "order-service-route";
    private static final String ORDER_PATH = "/api/v1/orders/1";

    private static final String FAIL_OPEN_METRIC = "ticketcraft.gateway.rate.limiter.fail.open";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private static final DisposableServer DOWNSTREAM = 
            HttpServer.create()
                      .host("127.0.0.1")
                      .port(0)
                      .handle((request, response) -> response.status(200).sendString(Mono.just("Order response")))
                      .bindNow();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));

        registry.add("ticketcraft.gateway.order-uri", () -> "http://127.0.0.1:" + DOWNSTREAM.port());

        // Обёртка должна завершить ожидание раньше Redis-клиента.
        registry.add("ticketcraft.gateway.rate-limiter.timeout", () -> "300ms");

        registry.add("spring.data.redis.timeout", () -> "5s");
    }

    @AfterAll
    static void stopResources(@Autowired LettuceConnectionFactory connectionFactory) {
        connectionFactory.stop();
        DOWNSTREAM.disposeNow();
    }

    @Test
    void shouldApplyConfiguredRedisRateLimit() {
        String token = registerUser("user-" + UUID.randomUUID());

        webTestClient.get()
                     .uri(ORDER_PATH)
                     .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                     .exchange()
                     .expectStatus()
                     .isOk()
                     .expectHeader()
                     .valueEquals("X-RateLimit-Replenish-Rate", "5")
                     .expectHeader()
                     .valueEquals("X-RateLimit-Burst-Capacity", "10")
                     .expectHeader()
                     .valueEquals("X-RateLimit-Requested-Tokens", "1")
                     .expectHeader()
                     .valueEquals("X-RateLimit-Remaining", "9")
                     .expectBody(String.class)
                     .isEqualTo("Order response");
    }

    @Test
    void shouldRejectExhaustedUserWithoutAffectingAnotherUser() throws Exception {

        String exhaustedUser = "user-" + UUID.randomUUID();

        String exhaustedToken = registerUser(exhaustedUser);
        String anotherToken = registerUser("user-" + UUID.randomUUID());

        exhaustBucket(exhaustedUser);

        webTestClient.get()
                     .uri(ORDER_PATH)
                     .header(HttpHeaders.AUTHORIZATION, "Bearer " + exhaustedToken)
                     .header("X-Correlation-ID", "redis-limit-test")
                     .exchange()
                     .expectStatus()
                     .isEqualTo(429)
                     .expectHeader()
                     .valueEquals("X-RateLimit-Remaining", "0")
                     .expectBody()
                     .jsonPath("$.status")
                     .isEqualTo(429)
                     .jsonPath("$.code")
                     .isEqualTo("RATE_LIMIT_EXCEEDED")
                     .jsonPath("$.instance")
                     .isEqualTo(ORDER_PATH)
                     .jsonPath("$.correlationId")
                     .isEqualTo("redis-limit-test");

        webTestClient.get()
                     .uri(ORDER_PATH)
                     .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken)
                     .exchange()
                     .expectStatus()
                     .isOk()
                     .expectHeader()
                     .valueEquals("X-RateLimit-Remaining", "9");
    }

    @Test
    void shouldAllowRequestAndRecordFailOpenWhenRedisIsPaused() throws Exception {

        String token = registerUser("user-" + UUID.randomUUID());

        // Предварительно устанавливаем соединение и прогружаем Lua-скрипт.
        webTestClient.get()
                     .uri(ORDER_PATH)
                     .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                     .exchange()
                     .expectStatus()
                     .isOk();

        double failuresBefore = failOpenCount();

        executeRedis("CLIENT", "PAUSE", "10000", "ALL");

        try {
            webTestClient.get()
                         .uri(ORDER_PATH)
                         .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                         .exchange()
                         .expectStatus()
                         .isOk()
                         .expectBody(String.class)
                         .isEqualTo("Order response");

            assertEquals(failuresBefore + 1, failOpenCount(), 0.0);
        } finally {
            executeRedis("CLIENT", "UNPAUSE");
        }
    }

    private String registerUser(String subject) {
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue(token)
                     .header("alg", "RS256")
                     .subject(subject)
                     .issuedAt(now)
                     .expiresAt(now.plusSeconds(300))
                     .build();

        when(jwtDecoder.decode(token)).thenReturn(Mono.just(jwt));

        return token;
    }

    /**
     * Создаёт пустую корзину без ожидания её исчерпания HTTP-запросами.
     *
     * <p>
     * Формат ключей соответствует RedisRateLimiter в Gateway 5.0.3. Будущая метка
     * времени исключает пополнение перед проверкой.
     */
    private void exhaustBucket(String subject) throws Exception {
        String prefix = "request_rate_limiter.{" + ROUTE_ID + ".user:" + subject + "}.";

        String script = "local time = redis.call('TIME'); " + "redis.call('SET', KEYS[1], 0, 'EX', 60); "
                + "redis.call('SET', KEYS[2], " + "tonumber(time[1]) + 3600, 'EX', 60); " + "return 1";

        executeRedis("EVAL", script, "2", prefix + "tokens", prefix + "timestamp");
    }

    private double failOpenCount() {
        double total = 0;

        for (Counter counter : meterRegistry.find(FAIL_OPEN_METRIC).tag("route", ROUTE_ID).counters()) {

            total += counter.count();
        }

        return total;
    }

    private void executeRedis(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "redis-cli";

        System.arraycopy(arguments, 0, command, 1, arguments.length);

        org.testcontainers.containers.Container.ExecResult result = REDIS.execInContainer(command);

        assertEquals(0, result.getExitCode(), result.getStderr());
    }
}