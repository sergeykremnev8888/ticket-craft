package ru.ticketcraft.gateway.exception.handler;

import java.net.ConnectException;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import io.netty.channel.ConnectTimeoutException;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayExceptionHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        if (ex instanceof ConnectTimeoutException) {
            response.setStatusCode(HttpStatus.GATEWAY_TIMEOUT);
            return response.setComplete();
        }

        if (ex instanceof ConnectException) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return response.setComplete();
        }

        return Mono.error(ex);
    }

}
