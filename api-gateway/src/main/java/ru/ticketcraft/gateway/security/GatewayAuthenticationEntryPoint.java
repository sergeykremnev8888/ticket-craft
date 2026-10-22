package ru.ticketcraft.gateway.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import ru.ticketcraft.gateway.exception.GatewayProblemWriter;

/**
 * Формирует ответ 401 с Bearer и телом ProblemDetail.
 */
@Component
public class GatewayAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final BearerTokenServerAuthenticationEntryPoint delegate = new BearerTokenServerAuthenticationEntryPoint();

    private final GatewayProblemWriter problemWriter;

    public GatewayAuthenticationEntryPoint(GatewayProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        ServerHttpResponseDecorator response = new ServerHttpResponseDecorator(exchange.getResponse()) {

            @Override
            public Mono<Void> setComplete() {
                return problemWriter.write(exchange,
                                           HttpStatus.UNAUTHORIZED,
                                           "UNAUTHORIZED",
                                           "A valid Bearer token is required.");
            }
        };

        ServerWebExchange decoratedExchange = exchange.mutate().response(response).build();
        return delegate.commence(decoratedExchange, exception);
    }
}