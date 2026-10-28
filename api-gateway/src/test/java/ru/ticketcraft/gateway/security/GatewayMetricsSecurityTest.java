package ru.ticketcraft.gateway.security;

import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "ticketcraft.gateway.security.metrics-public=false")
@AutoConfigureWebTestClient
class GatewayMetricsSecurityTest {

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @Test
    void shouldRejectRequestWithoutToken() {
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void shouldRejectTokenWithoutMetricsScope() {
        registerToken("user-token", "orders.read");

        client.get()
              .uri("/actuator/prometheus")
              .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
              .exchange()
              .expectStatus()
              .isForbidden();
    }

    @Test
    void shouldAllowTokenWithMetricsScope() {
        registerToken("metrics-token", "metrics.read");

        client.get()
              .uri("/actuator/prometheus")
              .header(HttpHeaders.AUTHORIZATION, "Bearer metrics-token")
              .exchange()
              .expectStatus()
              .isOk();
    }

    private void registerToken(String value, String scope) {
        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue(value)
                     .header("alg", "RS256")
                     .subject("test-user")
                     .issuedAt(now)
                     .expiresAt(now.plusSeconds(300))
                     .claim("scope", scope)
                     .build();

        when(jwtDecoder.decode(value)).thenReturn(Mono.just(jwt));
    }
}