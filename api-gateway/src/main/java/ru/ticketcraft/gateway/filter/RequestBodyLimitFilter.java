package ru.ticketcraft.gateway.filter;

import java.util.concurrent.TimeoutException;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import ru.ticketcraft.gateway.config.GatewayRequestLimitsProperties;
import ru.ticketcraft.gateway.exception.GatewayProblemWriter;

/**
 * Ограничивает фактический размер тела, включая chunked-запросы. Выполняется до
 * маршрутных фильтров и обращения к downstream.
 */
@Component
@Order(-190)
@EnableConfigurationProperties(GatewayRequestLimitsProperties.class)
public class RequestBodyLimitFilter implements GlobalFilter {

    private static final byte[] EMPTY_BYTES_ARRAY = new byte[0];

    private final GatewayRequestLimitsProperties properties;
    private final GatewayProblemWriter problemWriter;

    public RequestBodyLimitFilter(GatewayRequestLimitsProperties properties, GatewayProblemWriter problemWriter) {
        this.properties = properties;
        this.problemWriter = problemWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long contentLength = exchange.getRequest().getHeaders().getContentLength();

        // Быстрый отказ, если размер уже известен.
        int maxBodyBytes = properties.maxBodyBytes();
        if (contentLength > maxBodyBytes) {
            return rejectTooLarge(exchange);
        }

        // Проверяем реальные байты независимо от Content-Length.
        Mono<byte[]> body = DataBufferUtils.join(exchange.getRequest().getBody(), maxBodyBytes)
                                           .map(this::transferToArray)
                                           .defaultIfEmpty(EMPTY_BYTES_ARRAY)
                                           .timeout(properties.bodyReadTimeout());

        // Обрабатываем здесь только ошибки чтения тела.
        // Ошибки downstream не должны превращаться в REQUEST_BODY_TIMEOUT.
        return body.materialize().flatMap(signal -> {
            if (signal.isOnError()) {
                return toMonoError(exchange, signal);
            }

            ServerHttpRequest request = wrapRequestWithActualContentLength(exchange, signal);
            return chain.filter(exchange.mutate().request(request).build());
        });
    }

    private ServerHttpRequest wrapRequestWithActualContentLength(ServerWebExchange exchange, Signal<byte[]> signal) {
        byte[] bytes = signal.get();
        return new ServerHttpRequestDecorator(exchange.getRequest()) {

            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.remove(HttpHeaders.TRANSFER_ENCODING);
                headers.setContentLength(bytes.length);

                return HttpHeaders.readOnlyHttpHeaders(headers);
            }

            @Override
            public Flux<DataBuffer> getBody() {
                if (bytes.length == 0) {
                    return Flux.empty();
                }

                return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(bytes)));
            }
        };
    }

    private Mono<Void> toMonoError(ServerWebExchange exchange, Signal<byte[]> signal) {
        Throwable error = signal.getThrowable();

        if (error instanceof DataBufferLimitException) {
            return rejectTooLarge(exchange);
        }

        if (error instanceof TimeoutException) {
            return problemWriter.write(exchange,
                                       HttpStatus.REQUEST_TIMEOUT,
                                       "REQUEST_BODY_TIMEOUT",
                                       "Request body was not received in time.");
        }

        return Mono.error(error);
    }

    private byte[] transferToArray(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private Mono<Void> rejectTooLarge(ServerWebExchange exchange) {
        return problemWriter.write(exchange,
                                   HttpStatus.valueOf(413),
                                   "REQUEST_BODY_TOO_LARGE",
                                   "Request body exceeds the configured size limit.");
    }

}