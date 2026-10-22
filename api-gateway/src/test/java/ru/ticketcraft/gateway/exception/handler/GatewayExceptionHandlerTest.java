package ru.ticketcraft.gateway.exception.handler;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory;
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

class GatewayExceptionHandlerTest {

    private GatewayProblemWriter problemWriter;
    private GatewayExceptionHandler handler;
    private MockServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        problemWriter = mock(GatewayProblemWriter.class);
        handler = new GatewayExceptionHandler(problemWriter);

        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/catalog/events"));

        when(problemWriter.write(any(ServerWebExchange.class),
                                 any(HttpStatus.class),
                                 anyString(),
                                 anyString())).thenReturn(Mono.empty());
    }

    @Test
    void shouldWrite503ForConnectionRefused() {
        ConnectException exception = new ConnectException("Connection refused");

        assertHandled(exception, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
    }

    @Test
    void shouldWrite504ForConnectionTimeout() {
        ConnectTimeoutException exception = new ConnectTimeoutException("Connection timeout");

        assertHandled(exception, HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT");
    }

    @Test
    void shouldWrite504ForReadTimeout() {
        assertHandled(ReadTimeoutException.INSTANCE, HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT");
    }

    @Test
    void shouldWrite504ForExecutionTimeout() {
        TimeoutException exception = new TimeoutException("Execution timeout");

        assertHandled(exception, HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT");
    }

    @Test
    void shouldWrite504ForGatewayTimeoutStatusException() {
        ResponseStatusException exception = new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Response timeout");

        assertHandled(exception, HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT");
    }

    @Test
    void shouldWrite503ForCircuitBreakerStatusException() {
        SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException exception = circuitBreakerException(HttpStatus.SERVICE_UNAVAILABLE);

        assertHandled(exception, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
    }

    @Test
    void shouldWrite504ForCircuitBreakerStatusException() {
        SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException exception = circuitBreakerException(HttpStatus.GATEWAY_TIMEOUT);

        assertHandled(exception, HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT");
    }

    @Test
    void shouldPropagateUnknownException() {
        RuntimeException exception = new RuntimeException("Unknown error");

        assertPropagated(exception);
    }

    @Test
    void shouldPropagateUnrelatedResponseStatusException() {
        ResponseStatusException exception = new ResponseStatusException(HttpStatus.BAD_REQUEST);

        assertPropagated(exception);
    }

    @Test
    void shouldPropagateUnhandledCircuitBreakerStatusException() {
        SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException exception = circuitBreakerException(HttpStatus.BAD_GATEWAY);

        assertPropagated(exception);
    }

    @Test
    void shouldPropagateExceptionWhenResponseIsCommitted() {
        StepVerifier.create(exchange.getResponse().setComplete()).verifyComplete();

        ConnectException exception = new ConnectException("Connection refused");

        assertPropagated(exception);
    }

    private void assertHandled(Throwable exception, HttpStatus expectedStatus, String expectedCode) {

        StepVerifier.create(handler.handle(exchange, exception)).verifyComplete();

        verify(problemWriter).write(eq(exchange), eq(expectedStatus), eq(expectedCode), anyString());

        verifyNoMoreInteractions(problemWriter);
    }

    private void assertPropagated(Throwable exception) {
        StepVerifier.create(handler.handle(exchange, exception))
                    .expectErrorSatisfies(actual -> assertSame(exception, actual))
                    .verify();

        verifyNoInteractions(problemWriter);
    }

    private SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException circuitBreakerException(HttpStatus status) {

        SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException exception = mock(SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException.class);

        when(exception.getStatusCode()).thenReturn(status);

        return exception;
    }
}