package ru.ticketcraft.gateway.security;

import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;
import ru.ticketcraft.gateway.exception.GatewayProblemWriter;

/**
 * Запрещает прямые HTTP-запросы к fallback. Внутреннее перенаправление Circuit
 * Breaker остаётся доступным.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class InternalFallbackGuard implements WebFilter {

    private final GatewayProblemWriter problemWriter;

    public InternalFallbackGuard(GatewayProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();

        boolean fallbackPath = path.equals("/fallback") || path.startsWith("/fallback/");
        boolean internalFallback = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR) != null;
        if (fallbackPath && !internalFallback) {
            return problemWriter.write(exchange, HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
        }

        return chain.filter(exchange);
    }
}