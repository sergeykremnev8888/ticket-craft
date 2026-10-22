package ru.ticketcraft.gateway.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import reactor.test.StepVerifier;
import ru.ticketcraft.gateway.filter.CorrelationIdFilter;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayProblemWriterTest {

    @Autowired
    private GatewayProblemWriter problemWriter;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @Test
    void shouldWriteProblemWithCorrelationId() {
        MockServerWebExchange exchange = createExchange("/api/v1/catalog/events?search=test");

        exchange.getAttributes().put(CorrelationIdFilter.HEADER_NAME, "request-123");

        StepVerifier.create(problemWriter.write(exchange,
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "CATALOG_UNAVAILABLE",
                                                "Каталог временно недоступен."))
                    .verifyComplete();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());

        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, exchange.getResponse().getHeaders().getContentType());

        StepVerifier.create(exchange.getResponse().getBodyAsString()).assertNext(body -> {
            Map<?, ?> problem = jsonMapper.readValue(body, Map.class);

            assertEquals("about:blank", problem.get("type"));
            assertEquals("Service Unavailable", problem.get("title"));
            assertEquals(503, problem.get("status"));
            assertEquals("Каталог временно недоступен.", problem.get("detail"));
            assertEquals("/api/v1/catalog/events", problem.get("instance"));
            assertEquals("CATALOG_UNAVAILABLE", problem.get("code"));
            assertEquals("request-123", problem.get("correlationId"));

            // Дополнительные поля должны быть на верхнем уровне.
            assertFalse(problem.containsKey("properties"));
        }).verifyComplete();
    }

    @Test
    void shouldPreserveWwwAuthenticateHeader() {
        MockServerWebExchange exchange = createExchange("/api/v1/orders/1");

        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");

        StepVerifier.create(problemWriter.write(exchange,
                                                HttpStatus.UNAUTHORIZED,
                                                "UNAUTHORIZED",
                                                "A valid Bearer token is required."))
                    .verifyComplete();

        assertEquals("Bearer error=\"invalid_token\"",
                     exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void shouldOmitCorrelationIdWhenAttributeIsMissing() {
        MockServerWebExchange exchange = createExchange("/api/v1/orders/1");

        StepVerifier.create(problemWriter.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied."))
                    .verifyComplete();

        StepVerifier.create(exchange.getResponse().getBodyAsString()).assertNext(body -> {
            Map<?, ?> problem = jsonMapper.readValue(body, Map.class);

            assertFalse(problem.containsKey("correlationId"));
        }).verifyComplete();
    }

    @Test
    void shouldRejectWritingToCommittedResponse() {
        MockServerWebExchange exchange = createExchange("/api/v1/orders/1");

        exchange.getResponse().setStatusCode(HttpStatus.OK);

        StepVerifier.create(exchange.getResponse().setComplete()).verifyComplete();

        StepVerifier.create(problemWriter.write(exchange,
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "SERVICE_UNAVAILABLE",
                                                "Service is unavailable."))
                    .expectErrorSatisfies(exception -> assertInstanceOf(IllegalStateException.class, exception))
                    .verify();

        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
    }

    @Test
    void shouldUseSavedOriginalPathAfterInternalForward() {
        MockServerWebExchange exchange = createExchange("/fallback/order");

        exchange.getAttributes().put(CorrelationIdFilter.ORIGINAL_REQUEST_PATH_ATTRIBUTE, URI.create("/api/v1/orders"));

        StepVerifier.create(problemWriter.write(exchange,
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "ORDER_SERVICE_UNAVAILABLE",
                                                "Order service is temporarily unavailable."))
                    .verifyComplete();

        StepVerifier.create(exchange.getResponse().getBodyAsString()).assertNext(body -> {
            Map<?, ?> problem = jsonMapper.readValue(body, Map.class);

            assertEquals("/api/v1/orders", problem.get("instance"));
        }).verifyComplete();
    }

    private MockServerWebExchange createExchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path));
    }
}