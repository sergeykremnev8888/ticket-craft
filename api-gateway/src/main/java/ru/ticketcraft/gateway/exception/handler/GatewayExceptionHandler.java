package ru.ticketcraft.gateway.exception.handler;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import reactor.core.publisher.Mono;
import ru.ticketcraft.gateway.exception.GatewayProblemWriter;

@Component
@Order(-2)
public class GatewayExceptionHandler implements WebExceptionHandler {

    private final GatewayProblemWriter problemWriter;

    public GatewayExceptionHandler(GatewayProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(exception);
        }

        if (isTimeout(exception)) {
            return writeGatewayTimeout(exchange);
        }

        if (exception instanceof ConnectException) {
            return writeServiceUnavailable(exchange);
        }

        if (exception instanceof ResponseStatusException statusException
                && statusException.getStatusCode().value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
            return writeGatewayTimeout(exchange);
        }

        if (exception instanceof SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException statusException) {
            HttpStatus status = HttpStatus.resolve(statusException.getStatusCode().value());

            if (status == HttpStatus.GATEWAY_TIMEOUT) {
                return writeGatewayTimeout(exchange);
            }

            if (status == HttpStatus.SERVICE_UNAVAILABLE) {
                return writeServiceUnavailable(exchange);
            }
        }

        return Mono.error(exception);
    }

    private boolean isTimeout(Throwable exception) {
        return exception instanceof ConnectTimeoutException
                || exception instanceof ReadTimeoutException
                || exception instanceof TimeoutException;
    }

    private Mono<Void> writeGatewayTimeout(ServerWebExchange exchange) {
        return problemWriter.write(exchange,
                                   HttpStatus.GATEWAY_TIMEOUT,
                                   "GATEWAY_TIMEOUT",
                                   "The downstream service did not respond in time. "
                                           + "For state-changing requests, the operation "
                                           + "may still have completed.");
    }

    private Mono<Void> writeServiceUnavailable(ServerWebExchange exchange) {
        return problemWriter.write(exchange,
                                   HttpStatus.SERVICE_UNAVAILABLE,
                                   "SERVICE_UNAVAILABLE",
                                   "The downstream service is temporarily unavailable.");
    }
}