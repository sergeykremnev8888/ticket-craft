package ru.ticketcraft.gateway.controller;

import java.util.concurrent.TimeoutException;

import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import reactor.core.publisher.Mono;
import ru.ticketcraft.gateway.exception.GatewayProblemWriter;

@RestController
@RequestMapping("/fallback")
public class FallbackController {

    private final GatewayProblemWriter problemWriter;

    public FallbackController(GatewayProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @GetMapping("/catalog")
    public Mono<Void> catalogFallback(ServerWebExchange exchange) {
        boolean timeout = isTimeout(exchange);
        return problemWriter.write(exchange,
                                   timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE,
                                   timeout ? "CATALOG_TIMEOUT" : "CATALOG_UNAVAILABLE",
                                   timeout ? "Catalog service did not respond in time."
                                           : "Catalog service is temporarily unavailable.");
    }

    @RequestMapping("/order")
    public Mono<Void> orderFallback(ServerWebExchange exchange) {
        boolean timeout = isTimeout(exchange);
        String detail = timeout 
                ? "Order service did not respond in time."
                : "Order service is temporarily unavailable.";

        detail += " For state-changing requests, the operation may still have completed. Retry with the same Idempotency-Key.";

        return problemWriter.write(exchange,
                                   timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE,
                                   timeout ? "ORDER_SERVICE_TIMEOUT" : "ORDER_SERVICE_UNAVAILABLE",
                                   detail);
    }

    private boolean isTimeout(ServerWebExchange exchange) {
        Throwable exception = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);

        // Ограничиваем обход на случай некорректной цепочки причин.
        for (int depth = 0; exception != null && depth < 16; depth++) {
            if (exception instanceof ConnectTimeoutException || exception instanceof ReadTimeoutException
                    || exception instanceof TimeoutException) {
                return true;
            }

            if (exception instanceof ResponseStatusException statusException
                    && statusException.getStatusCode().value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
                return true;
            }

            if (exception instanceof SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException statusException
                    && statusException.getStatusCode().value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
                return true;
            }

            exception = exception.getCause();
        }

        return false;
    }
}