package ru.ticketcraft.gateway.ratelimiter;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import ru.ticketcraft.gateway.exception.GatewayProblemWriter;

/**
 * Дополняет стандартный RequestRateLimiter ответами ProblemDetail.
 *
 * <p>
 * Преобразует только локальные отказы проверки лимита. Ответы downstream
 * передаются без изменений.
 */
@Component
public class ProblemRequestRateLimiterGatewayFilterFactory
        extends AbstractGatewayFilterFactory<RequestRateLimiterGatewayFilterFactory.Config> {

    private final RequestRateLimiterGatewayFilterFactory delegate;
    private final GatewayProblemWriter problemWriter;

    public ProblemRequestRateLimiterGatewayFilterFactory(RequestRateLimiterGatewayFilterFactory delegate,
                                                         GatewayProblemWriter problemWriter) {

        super(RequestRateLimiterGatewayFilterFactory.Config.class);

        this.delegate = delegate;
        this.problemWriter = problemWriter;
    }

    @Override
    public GatewayFilter apply(RequestRateLimiterGatewayFilterFactory.Config config) {
        GatewayFilter rateLimiterFilter = delegate.apply(config);
        return (exchange, chain) -> Mono.defer(() -> {
            ServerHttpResponseDecorator response = createResponseDecorator(exchange);
            ServerWebExchange decoratedExchange = exchange.mutate().response(response).build();

            return rateLimiterFilter.filter(decoratedExchange,
                                            allowedExchange -> chain.filter(allowedExchange.mutate()
                                                                                           .response(exchange.getResponse())
                                                                                           .build()));
        });
    }

    private ServerHttpResponseDecorator createResponseDecorator(ServerWebExchange exchange) {
        return new ServerHttpResponseDecorator(exchange.getResponse()) {

            @Override
            public Mono<Void> setComplete() {
                HttpStatusCode status = getStatusCode();

                if (status == HttpStatus.TOO_MANY_REQUESTS) {
                    return problemWriter.write(exchange,
                                               HttpStatus.TOO_MANY_REQUESTS,
                                               "RATE_LIMIT_EXCEEDED",
                                               "The request rate limit has been exceeded.");
                }

                if (status == HttpStatus.FORBIDDEN) {
                    return problemWriter.write(exchange,
                                               HttpStatus.FORBIDDEN,
                                               "RATE_LIMIT_KEY_MISSING",
                                               "The rate limit key could not be determined.");
                }

                return super.setComplete();
            }
        };
    }
}