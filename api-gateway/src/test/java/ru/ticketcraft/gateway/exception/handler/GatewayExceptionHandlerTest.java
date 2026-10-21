package ru.ticketcraft.gateway.exception.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.ConnectException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import io.netty.channel.ConnectTimeoutException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

public class GatewayExceptionHandlerTest {

    private GatewayExceptionHandler handler;
    private MockServerWebExchange exchange;
    
    @BeforeEach
    public void setUp() {
        handler = new GatewayExceptionHandler();
        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/debug/slow"));
    }
    
    @Test
    public void shouldReturn503ForConnectionRefused() {
        ConnectException exception = new ConnectException("Connection refused");
        Mono<Void> result = handler.handle(exchange, exception);

        StepVerifier.create(result).verifyComplete();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
    }

    @Test
    public void shouldReturn504ForConnectionTimeout() {
        ConnectTimeoutException exception = new ConnectTimeoutException("Connection timeout");
        Mono<Void> result = handler.handle(exchange, exception);

        StepVerifier.create(result).verifyComplete();

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, exchange.getResponse().getStatusCode());
    }

    @Test
    public void shouldPropagateUnknownException() {
        RuntimeException exception = new RuntimeException("Unknown error");
        Mono<Void> result = handler.handle(exchange, exception);

        StepVerifier.create(result).expectErrorSatisfies(actual -> assertSame(exception, actual)).verify();
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    public void shouldPropagateExceptionWhenResponseCommitted() {
        exchange.getResponse().setComplete().block();
        assertTrue(exchange.getResponse().isCommitted());

        ConnectException exception = new ConnectException("Connection refused");
        Mono<Void> result = handler.handle(exchange, exception);

        StepVerifier.create(result).expectErrorSatisfies(actual -> assertSame(exception, actual)).verify();
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    public void shouldReturnSameStatusForCircuitBreakerStatusCodeException() {
        SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException exception =
                mock(SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException.class);
        when(exception.getStatusCode()).thenReturn(HttpStatus.SERVICE_UNAVAILABLE);

        Mono<Void> result = handler.handle(exchange, exception);
        StepVerifier.create(result).verifyComplete();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
    }
}
