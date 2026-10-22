package ru.ticketcraft.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.test.StepVerifier;

class CorrelationIdFilterTest {

    private static final String HEADER = CorrelationIdFilter.HEADER_NAME;

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void shouldGenerateCorrelationIdWhenHeaderIsMissing() {
        assertGeneratedId();
    }

    @Test
    void shouldGenerateCorrelationIdWhenHeaderIsEmpty() {
        assertGeneratedId("");
    }

    @Test
    void shouldGenerateCorrelationIdWhenHeaderIsBlank() {
        assertGeneratedId("   ");
    }

    @Test
    void shouldGenerateCorrelationIdWhenHeaderContainsSpaces() {
        assertGeneratedId("request 123");
    }

    @Test
    void shouldGenerateCorrelationIdWhenHeaderIsTooLong() {
        assertGeneratedId("a".repeat(129));
    }

    @Test
    void shouldGenerateCorrelationIdWhenHeaderHasMultipleValues() {
        assertGeneratedId("request-111", "request-222");
    }

    @Test
    void shouldPreserveValidCorrelationId() {
        assertPreservedId("request-123_ABC.456");
    }

    @Test
    void shouldPreserveCorrelationIdWithMaximumAllowedLength() {
        assertPreservedId("a".repeat(128));
    }

    @Test
    void shouldReplaceCorrelationIdProvidedByDownstream() {
        String correlationId = "request-123";

        MockServerWebExchange exchange = createExchange(correlationId);

        WebFilterChain chain = updatedExchange -> {
            updatedExchange.getResponse().getHeaders().add(HEADER, "downstream-id");

            return updatedExchange.getResponse().setComplete();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(List.of(correlationId), exchange.getResponse().getHeaders().get(HEADER));
    }

    private void assertGeneratedId(String... incomingIds) {
        String actualId = executeFilter(incomingIds);

        assertDoesNotThrow(() -> UUID.fromString(actualId));

        for (String incomingId : incomingIds) {
            assertNotEquals(incomingId, actualId);
        }
    }

    private void assertPreservedId(String incomingId) {
        assertEquals(incomingId, executeFilter(incomingId));
    }

    private String executeFilter(String... incomingIds) {
        MockServerWebExchange exchange = createExchange(incomingIds);

        AtomicReference<List<String>> downstreamIds = new AtomicReference<>();
        AtomicReference<String> attributeId = new AtomicReference<>();

        WebFilterChain chain = updatedExchange -> {
            downstreamIds.set(updatedExchange.getRequest().getHeaders().get(HEADER));

            attributeId.set(updatedExchange.getAttribute(HEADER));

            return updatedExchange.getResponse().setComplete();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String responseId = exchange.getResponse().getHeaders().getFirst(HEADER);

        assertNotNull(responseId);
        assertEquals(List.of(responseId), downstreamIds.get());
        assertEquals(responseId, attributeId.get());
        assertEquals(List.of(responseId), exchange.getResponse().getHeaders().get(HEADER));

        return responseId;
    }

    private MockServerWebExchange createExchange(String... incomingIds) {
        var request = MockServerHttpRequest.get("/api/v1/catalog/events");

        if (incomingIds.length > 0) {
            request.header(HEADER, incomingIds);
        }

        return MockServerWebExchange.from(request);
    }
}