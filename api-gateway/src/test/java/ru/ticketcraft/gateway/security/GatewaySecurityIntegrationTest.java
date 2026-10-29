package ru.ticketcraft.gateway.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import ru.ticketcraft.gateway.filter.CorrelationIdFilter;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class GatewaySecurityIntegrationTest {

    private static final String CORRELATION_HEADER = CorrelationIdFilter.HEADER_NAME;

    private static final String CORRELATION_ID = "security-test-123";

    private static final String ORDER_PATH = "/api/v1/orders/1";
    private static final String FORBIDDEN_PATH = "/internal/forbidden";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @Test
    void shouldReturn401ProblemWhenTokenIsMissing() {
        WebTestClient.ResponseSpec response = webTestClient.get()
                                                           .uri(ORDER_PATH)
                                                           .header(CORRELATION_HEADER, CORRELATION_ID)
                                                           .exchange();

        response.expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");

        assertProblem(response, 401, "UNAUTHORIZED", ORDER_PATH, CORRELATION_ID);
    }

    @Test
    void shouldReturn401ProblemWhenTokenIsInvalid() {
        when(jwtDecoder.decode("invalid-token")).thenReturn(Mono.error(new BadJwtException("Invalid token signature")));

        WebTestClient.ResponseSpec response = webTestClient.get()
                                                           .uri(ORDER_PATH)
                                                           .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                                                           .header(CORRELATION_HEADER, CORRELATION_ID)
                                                           .exchange();

        response.expectHeader().valueMatches(HttpHeaders.WWW_AUTHENTICATE, ".*error=\"invalid_token\".*");

        assertProblem(response, 401, "UNAUTHORIZED", ORDER_PATH, CORRELATION_ID);
    }

    @Test
    void shouldReturn403ProblemForAuthenticatedUserOnForbiddenPath() {
        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue("valid-token")
                     .header("alg", "RS256")
                     .subject("user-123")
                     .issuedAt(now)
                     .expiresAt(now.plusSeconds(300))
                     .claim("scope", "orders.read")
                     .build();

        when(jwtDecoder.decode("valid-token")).thenReturn(Mono.just(jwt));

        WebTestClient.ResponseSpec response = webTestClient.get()
                                                           .uri(FORBIDDEN_PATH)
                                                           .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                                                           .header(CORRELATION_HEADER, CORRELATION_ID)
                                                           .exchange();

        assertProblem(response, 403, "FORBIDDEN", FORBIDDEN_PATH, CORRELATION_ID);
    }

    @Test
    void shouldGenerateMatchingCorrelationIdForUnauthorizedResponse() {
        EntityExchangeResult<byte[]> result = webTestClient.get()
                                                           .uri(ORDER_PATH)
                                                           .exchange()
                                                           .expectStatus()
                                                           .isUnauthorized()
                                                           .expectHeader()
                                                           .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                                                           .expectBody()
                                                           .returnResult();

        String correlationId = result.getResponseHeaders().getFirst(CORRELATION_HEADER);

        assertNotNull(correlationId);
        assertDoesNotThrow(() -> UUID.fromString(correlationId));

        byte[] body = result.getResponseBody();
        assertNotNull(body);

        Map<?, ?> problem = jsonMapper.readValue(body, Map.class);

        assertEquals(correlationId, problem.get("correlationId"));
        assertEquals("UNAUTHORIZED", problem.get("code"));
        assertEquals(401, problem.get("status"));
        assertEquals(ORDER_PATH, problem.get("instance"));
    }

    private void assertProblem(WebTestClient.ResponseSpec response,
                               int status,
                               String code,
                               String instance,
                               String correlationId) {

        response.expectStatus()
                .isEqualTo(status)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader()
                .valueEquals(CORRELATION_HEADER, correlationId)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("about:blank")
                .jsonPath("$.status")
                .isEqualTo(status)
                .jsonPath("$.code")
                .isEqualTo(code)
                .jsonPath("$.instance")
                .isEqualTo(instance)
                .jsonPath("$.correlationId")
                .isEqualTo(correlationId)
                .jsonPath("$.title")
                .isNotEmpty()
                .jsonPath("$.detail")
                .isNotEmpty()
                .jsonPath("$.properties")
                .doesNotExist();
    }
}