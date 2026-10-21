package ru.ticketcraft.gateway.filter;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Добавляет идентификатор корреляции к запросу и ответу.
 *
 * <p>
 * Выполняется перед Spring Security, поэтому идентификатор присутствует в том
 * числе в ответах об отказе в доступе.
 *
 * <p>
 * Принимает единственный непустой входящий идентификатор, содержащий не более
 * 128 разрешённых символов. При отсутствии или некорректном значении генерирует
 * UUID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements WebFilter {

    public static final String HTTP_HEADER_CORRELATION_ID = "X-Correlation-ID";

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             WebFilterChain chain) {
        String correlationId = resolveCorrelationId(exchange);

        ServerHttpRequest request = exchange.getRequest()
                                            .mutate()
                                            .headers(headers -> headers.set(HTTP_HEADER_CORRELATION_ID, correlationId))
                                            .build();

        ServerWebExchange updatedExchange = exchange.mutate().request(request).build();

        updatedExchange.getAttributes().put(HTTP_HEADER_CORRELATION_ID, correlationId);

        updatedExchange.getResponse().beforeCommit(() -> {
            updatedExchange.getResponse().getHeaders().set(HTTP_HEADER_CORRELATION_ID, correlationId);

            return Mono.empty();
        });

        return chain.filter(updatedExchange);
    }

    private String resolveCorrelationId(ServerWebExchange exchange) {
        List<String> values = exchange.getRequest().getHeaders().get(HTTP_HEADER_CORRELATION_ID);

        if (values != null && values.size() == 1) {
            String value = values.getFirst();

            if (value != null && VALID_ID.matcher(value).matches()) {
                return value;
            }
        }

        return UUID.randomUUID().toString();
    }
}
