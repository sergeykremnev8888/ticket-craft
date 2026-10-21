package ru.ticketcraft.gateway.filter;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
@Order(-100)
public class CorrelationIdFilter implements GlobalFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    private static final String HTTP_HEADER_CORRELATION_ID = "X-Correlation-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incomingId = exchange.getRequest().getHeaders().getFirst(HTTP_HEADER_CORRELATION_ID);

        final String correlationId = (incomingId == null || incomingId.isBlank()) 
                ? UUID.randomUUID().toString() : incomingId;

        exchange.getResponse().getHeaders().set(HTTP_HEADER_CORRELATION_ID, correlationId);

        ServerHttpRequest updatedRequest = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HTTP_HEADER_CORRELATION_ID, correlationId)).build();

        ServerWebExchange updatedExchange = exchange.mutate().request(updatedRequest).build();

        long startTime = System.nanoTime();

        return chain.filter(updatedExchange).doFinally(signalType -> {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startTime
            );

            HttpStatusCode status = updatedExchange.getResponse().getStatusCode();

            log.info(
                    "Gateway request: method={}, path={}, correlationId={}, status={}, durationMs={}",
                    updatedRequest.getMethod(),
                    updatedRequest.getURI().getRawPath(),
                    correlationId,
                    status,
                    durationMs
            );
        });
    }

}
