package ru.ticketcraft.gateway.controller;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.ticketcraft.gateway.exception.GatewayProblemWriter;

class FallbackControllerTest {

    private GatewayProblemWriter problemWriter;
    private FallbackController controller;

    @BeforeEach
    void setUp() {
        problemWriter = mock(GatewayProblemWriter.class);
        controller = new FallbackController(problemWriter);

        when(problemWriter.write(any(ServerWebExchange.class),
                                 any(HttpStatus.class),
                                 anyString(),
                                 anyString(),
                                 any(URI.class))).thenReturn(Mono.empty());
    }

    @Test
    void shouldReturn503WhenCatalogConnectionFails() {
        MockServerWebExchange exchange = createExchange("/fallback/catalog",
                                                        "http://localhost:8080/api/v1/catalog/events",
                                                        new ConnectException("Connection refused"));

        verifyCatalogFallback(exchange, HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE");
    }

    @Test
    void shouldReturn503WhenFailureCauseIsMissing() {
        MockServerWebExchange exchange = createExchange("/fallback/catalog",
                                                        "http://localhost:8080/api/v1/catalog/events",
                                                        null);

        verifyCatalogFallback(exchange, HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE");
    }

    @Test
    void shouldReturn504ForConnectionTimeout() {
        MockServerWebExchange exchange = createExchange("/fallback/catalog",
                                                        "http://localhost:8080/api/v1/catalog/events",
                                                        new ConnectTimeoutException("Connection timeout"));

        verifyCatalogFallback(exchange, HttpStatus.GATEWAY_TIMEOUT, "CATALOG_TIMEOUT");
    }

    @Test
    void shouldReturn504ForReadTimeout() {
        MockServerWebExchange exchange = createExchange("/fallback/catalog",
                                                        "http://localhost:8080/api/v1/catalog/events",
                                                        ReadTimeoutException.INSTANCE);

        verifyCatalogFallback(exchange, HttpStatus.GATEWAY_TIMEOUT, "CATALOG_TIMEOUT");
    }

    @Test
    void shouldReturn504ForWrappedExecutionTimeout() {
        RuntimeException exception = new RuntimeException("Wrapped failure", new TimeoutException("Execution timeout"));

        MockServerWebExchange exchange = createExchange("/fallback/catalog",
                                                        "http://localhost:8080/api/v1/catalog/events",
                                                        exception);

        verifyCatalogFallback(exchange, HttpStatus.GATEWAY_TIMEOUT, "CATALOG_TIMEOUT");
    }

    @Test
    void shouldReturn504ForGatewayTimeoutStatusException() {
        MockServerWebExchange exchange = createExchange("/fallback/catalog",
                                                        "http://localhost:8080/api/v1/catalog/events",
                                                        new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT));

        verifyCatalogFallback(exchange, HttpStatus.GATEWAY_TIMEOUT, "CATALOG_TIMEOUT");
    }

    @Test
    void shouldUseFirstOriginalPathAndExcludeQueryParameters() {
        MockServerWebExchange exchange = createExchange("/fallback/catalog",
                                                        "http://localhost:8080/api/v1/catalog/events?search=private",
                                                        new ConnectException("Connection refused"));

        Set<URI> originalUris = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);

        originalUris.add(URI.create("http://localhost:8081/internal/events"));

        verifyCatalogFallback(exchange, HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE");
    }

    @Test
    void shouldUseCurrentPathWhenOriginalUriIsMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/fallback/catalog"));

        StepVerifier.create(controller.catalogFallback(exchange)).verifyComplete();

        verify(problemWriter).write(eq(exchange),
                                    eq(HttpStatus.SERVICE_UNAVAILABLE),
                                    eq("CATALOG_UNAVAILABLE"),
                                    anyString(),
                                    eq(URI.create("/fallback/catalog")));

        verifyNoMoreInteractions(problemWriter);
    }

    @Test
    void shouldReturn503ForOrderAndWarnAboutUnknownResult() {
        MockServerWebExchange exchange = createExchange("/fallback/order",
                                                        "http://localhost:8080/api/v1/orders",
                                                        new ConnectException("Connection refused"));

        verifyOrderFallback(exchange, HttpStatus.SERVICE_UNAVAILABLE, "ORDER_SERVICE_UNAVAILABLE");
    }

    @Test
    void shouldReturn504ForOrderAndWarnAboutUnknownResult() {
        MockServerWebExchange exchange = createExchange("/fallback/order",
                                                        "http://localhost:8080/api/v1/orders",
                                                        new TimeoutException("Execution timeout"));

        verifyOrderFallback(exchange, HttpStatus.GATEWAY_TIMEOUT, "ORDER_SERVICE_TIMEOUT");
    }

    @Test
    void shouldPropagateWriterFailure() {
        MockServerWebExchange exchange = createExchange("/fallback/catalog",
                                                        "http://localhost:8080/api/v1/catalog/events",
                                                        null);

        RuntimeException failure = new RuntimeException("Serialization failed");

        when(problemWriter.write(eq(exchange),
                                 any(HttpStatus.class),
                                 anyString(),
                                 anyString(),
                                 any(URI.class))).thenReturn(Mono.error(failure));

        StepVerifier.create(controller.catalogFallback(exchange))
                    .expectErrorSatisfies(actual -> assertSame(failure, actual))
                    .verify();
    }

    private void verifyCatalogFallback(MockServerWebExchange exchange, HttpStatus expectedStatus, String expectedCode) {

        StepVerifier.create(controller.catalogFallback(exchange)).verifyComplete();

        verify(problemWriter).write(eq(exchange),
                                    eq(expectedStatus),
                                    eq(expectedCode),
                                    anyString(),
                                    eq(URI.create("/api/v1/catalog/events")));

        verifyNoMoreInteractions(problemWriter);
    }

    private void verifyOrderFallback(MockServerWebExchange exchange, HttpStatus expectedStatus, String expectedCode) {

        StepVerifier.create(controller.orderFallback(exchange)).verifyComplete();

        verify(problemWriter).write(eq(exchange),
                                    eq(expectedStatus),
                                    eq(expectedCode),
                                    contains("operation may still have completed"),
                                    eq(URI.create("/api/v1/orders")));

        verifyNoMoreInteractions(problemWriter);
    }

    private MockServerWebExchange createExchange(String fallbackPath, String originalUrl, Throwable exception) {

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(fallbackPath));

        Set<URI> originalUris = new LinkedHashSet<>();
        originalUris.add(URI.create(originalUrl));

        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR, originalUris);

        if (exception != null) {
            exchange.getAttributes().put(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR, exception);
        }

        return exchange;
    }
}