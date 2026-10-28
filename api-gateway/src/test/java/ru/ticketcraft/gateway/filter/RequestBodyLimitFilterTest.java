package ru.ticketcraft.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.ticketcraft.gateway.config.GatewayRequestLimitsProperties;
import ru.ticketcraft.gateway.exception.GatewayProblemWriter;

class RequestBodyLimitFilterTest {

    private GatewayProblemWriter writer;
    private RequestBodyLimitFilter filter;

    @BeforeEach
    void setUp() {
        writer = mock(GatewayProblemWriter.class);

        when(writer.write(any(), any(), anyString(), anyString())).thenReturn(Mono.empty());

        GatewayRequestLimitsProperties properties = new GatewayRequestLimitsProperties(DataSize.ofBytes(8),
                Duration.ofSeconds(2));
        filter = new RequestBodyLimitFilter(properties, writer);
    }

    @Test
    void shouldRejectDeclaredOversizedBody() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/orders")
                                                                                         .contentLength(9)
                                                                                         .body("123456789"));

        StepVerifier.create(filter.filter(exchange,
                                          ignored -> Mono.error(new AssertionError("Downstream must not be called"))))
                    .verifyComplete();

        verify(writer).write(eq(exchange), eq(HttpStatus.valueOf(413)), eq("REQUEST_BODY_TOO_LARGE"), anyString());
    }

    @Test
    void shouldRejectOversizedBodyWithoutContentLength() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/orders")
                                                                                         .body(Flux.just(buffer("12345"),
                                                                                                         buffer("67890"))));

        assertEquals(-1L, exchange.getRequest().getHeaders().getContentLength());

        StepVerifier.create(filter.filter(exchange,
                                          ignored -> Mono.error(new AssertionError("Downstream must not be called"))))
                    .verifyComplete();

        verify(writer).write(eq(exchange), eq(HttpStatus.valueOf(413)), eq("REQUEST_BODY_TOO_LARGE"), anyString());
    }

    @Test
    void shouldForwardBodyUnchangedAtExactLimit() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/orders")
                                                                                         .body(Flux.just(buffer("1234"),
                                                                                                         buffer("5678"))));

        StepVerifier.create(filter.filter(exchange, forwarded -> {
            assertEquals(8L, forwarded.getRequest().getHeaders().getContentLength());

            return DataBufferUtils.join(forwarded.getRequest().getBody()).doOnNext(body -> {
                try {
                    byte[] actual = new byte[body.readableByteCount()];
                    body.read(actual);

                    assertArrayEquals("12345678".getBytes(StandardCharsets.UTF_8), actual);
                } finally {
                    DataBufferUtils.release(body);
                }
            }).then();
        })).verifyComplete();
    }

    @Test
    void shouldForwardEmptyBody() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/catalog/events"));

        StepVerifier.create(filter.filter(exchange, forwarded -> {
            assertEquals(0L, forwarded.getRequest().getHeaders().getContentLength());

            return forwarded.getRequest()
                            .getBody()
                            .count()
                            .doOnNext(count -> assertEquals(0L, count.longValue()))
                            .then();
        })).verifyComplete();
    }

    @Test
    void shouldTimeoutWhileReadingBody() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/orders")
                                                                                         .body(Flux.<DataBuffer>never()));

        StepVerifier.withVirtualTime(() -> filter.filter(exchange,
                                                         ignored -> Mono.error(new AssertionError(
                                                                 "Downstream must not be called"))))
                    .thenAwait(Duration.ofSeconds(3))
                    .verifyComplete();

        verify(writer).write(eq(exchange), eq(HttpStatus.REQUEST_TIMEOUT), eq("REQUEST_BODY_TIMEOUT"), anyString());
    }

    @Test
    void shouldNotConvertDownstreamTimeoutIntoBodyTimeout() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/orders")
                                                                                         .body("{}"));

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(new TimeoutException())))
                    .expectError(TimeoutException.class)
                    .verify();
    }

    private DataBuffer buffer(String value) {
        return DefaultDataBufferFactory.sharedInstance.wrap(value.getBytes(StandardCharsets.UTF_8));
    }
}