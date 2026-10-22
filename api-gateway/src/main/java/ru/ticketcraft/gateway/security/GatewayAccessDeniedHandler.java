package ru.ticketcraft.gateway.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import ru.ticketcraft.gateway.exception.GatewayProblemWriter;

/**
 * Формирует ответ 403 с телом ProblemDetail.
 */
@Component
public class GatewayAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final BearerTokenServerAccessDeniedHandler delegate = new BearerTokenServerAccessDeniedHandler();

    private final GatewayProblemWriter problemWriter;

    public GatewayAccessDeniedHandler(GatewayProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException exception) {
        ServerHttpResponseDecorator response = new ServerHttpResponseDecorator(exchange.getResponse()) {

            @Override
            public Mono<Void> setComplete() {
                return problemWriter.write(exchange,
                                           HttpStatus.FORBIDDEN,
                                           "FORBIDDEN",
                                           "Access to this resource is denied.");
            }
        };

        ServerWebExchange decoratedExchange = exchange.mutate().response(response).build();
        return delegate.handle(decoratedExchange, exception);
    }
}