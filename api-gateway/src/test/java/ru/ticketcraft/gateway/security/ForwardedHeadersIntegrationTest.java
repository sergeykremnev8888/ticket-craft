package ru.ticketcraft.gateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import ru.ticketcraft.gateway.ratelimiter.ResilientRateLimiter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ForwardedHeadersIntegrationTest {

    private static final String CATALOG_PATH = "/api/v1/catalog/events";
    private static final String ROUTE_ID = "catalog-service-route";

    private static final DisposableServer DOWNSTREAM = 
            HttpServer.create()
                      .host("127.0.0.1")
                      .port(0)
                      .handle((request, response) -> {
                          // Возвращаем значения заголовков, дошедшие до сервиса.
                          String headers = List.of("Forwarded",
                                                   "X-Forwarded-For",
                                                   "X-Forwarded-Host",
                                                   "X-Forwarded-Proto",
                                                   "X-Forwarded-Port",
                                                   "X-Forwarded-Prefix")
                                               .stream()
                                               .map(name -> name + "=" + request.requestHeaders().getAll(name))
                                               .reduce("", (left, right) -> left + "\n" + right);

                          return response.status(200).sendString(Mono.just(headers));
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

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.disposeNow();
    }

    @Test
    void shouldIgnoreSpoofedForwardedHeadersAndKeepSameRateLimitKey() {
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(Mono.just(new Response(true, Map.of())));

        WebTestClient httpClient = client.mutate().baseUrl("http://127.0.0.1:" + gatewayPort).build();

        // Обычный запрос: запоминаем ключ через Mockito.
        httpClient.get().uri(CATALOG_PATH).exchange().expectStatus().isOk().expectBody(String.class);

        // Пытаемся подменить IP, внешний адрес, протокол и префикс.
        httpClient.get()
                  .uri(CATALOG_PATH)
                  .header("Forwarded", "for=198.51.100.20;host=spoofed.example;proto=https")
                  .header("X-Forwarded-For", "203.0.113.30")
                  .header("X-Forwarded-Host", "spoofed.example")
                  .header("X-Forwarded-Proto", "https")
                  .header("X-Forwarded-Port", "444")
                  .header("X-Forwarded-Prefix", "/spoofed-prefix")
                  .exchange()
                  .expectStatus()
                  .isOk()
                  .expectBody(String.class)
                  .value(headers -> {
                      assertFalse(headers.contains("198.51.100.20"));
                      assertFalse(headers.contains("203.0.113.30"));
                      assertFalse(headers.contains("spoofed.example"));
                      assertFalse(headers.contains("/spoofed-prefix"));
                      assertFalse(headers.contains("https"));
                      assertFalse(headers.contains("[444]"));
                  });

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

        verify(rateLimiter, times(2)).isAllowed(eq(ROUTE_ID), keyCaptor.capture());

        assertEquals(List.of("127.0.0.1", "127.0.0.1"),
                     keyCaptor.getAllValues(),
                     "Подмена заголовков не должна изменять ключ rate limiting");
    }
}