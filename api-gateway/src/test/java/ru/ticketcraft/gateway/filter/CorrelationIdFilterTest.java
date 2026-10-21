package ru.ticketcraft.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-ID";

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
    }

    @Test
    void shouldGenerateCorrelationIdWhenHeaderIsMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/debug/slow"));

        AtomicReference<String> downstreamId = new AtomicReference<>();

        GatewayFilterChain chain = updatedExchange -> {
            String id = updatedExchange.getRequest().getHeaders().getFirst(HEADER);

            downstreamId.set(id);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String responseId = exchange.getResponse().getHeaders().getFirst(HEADER);

        assertNotNull(responseId);
        assertDoesNotThrow(() -> UUID.fromString(responseId));
        assertEquals(responseId, downstreamId.get());
    }

    @Test
    void shouldPreserveIncomingCorrelationId() {
        String incomingId = "request-123";

        MockServerWebExchange exchange = MockServerWebExchange
                .from(MockServerHttpRequest.get("/debug/slow").header(HEADER, incomingId));

        AtomicReference<String> downstreamId = new AtomicReference<>();

        GatewayFilterChain chain = updatedExchange -> {
            downstreamId.set(updatedExchange.getRequest().getHeaders().getFirst(HEADER));

            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(incomingId, downstreamId.get());

        assertEquals(incomingId, exchange.getResponse().getHeaders().getFirst(HEADER));
    }

    @Test
    void shouldGenerateCorrelationIdWhenHeaderIsBlank() {
        MockServerWebExchange exchange = MockServerWebExchange
                .from(MockServerHttpRequest.get("/debug/slow").header(HEADER, "   "));

        AtomicReference<String> downstreamId = new AtomicReference<>();

        GatewayFilterChain chain = updatedExchange -> {
            downstreamId.set(updatedExchange.getRequest().getHeaders().getFirst(HEADER));

            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String responseId = exchange.getResponse().getHeaders().getFirst(HEADER);

        assertNotNull(responseId);
        assertDoesNotThrow(() -> UUID.fromString(responseId));
        assertEquals(responseId, downstreamId.get());
    }

    @Test
    void shouldReturnFirstCorrelationIdWhenHeaderIncludesMultiple() {
        MockServerWebExchange exchange = MockServerWebExchange
                .from(MockServerHttpRequest.get("/debug/slow").header(HEADER, "request-111").header(HEADER, "request-222"));

        AtomicReference<List<String>> downstreamIds = new AtomicReference<>();

        GatewayFilterChain chain = updatedExchange -> {
            downstreamIds.set(updatedExchange.getRequest()
                    .getHeaders()
                    .get(HEADER));

            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String responseId = exchange.getResponse().getHeaders().getFirst(HEADER);

        assertEquals("request-111", responseId);
        assertEquals(List.of("request-111"), downstreamIds.get());
    }
}