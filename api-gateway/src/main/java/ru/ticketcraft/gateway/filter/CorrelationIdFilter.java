package ru.ticketcraft.gateway.filter;

import java.net.URI;
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

    public static final String ORIGINAL_REQUEST_PATH_ATTRIBUTE =
            CorrelationIdFilter.class.getName() + ".originalRequestPath";

    public static final String HEADER_NAME = "X-Correlation-ID";

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             WebFilterChain chain) {
        URI originalPath = URI.create(exchange.getRequest().getURI().getRawPath());
        exchange.getAttributes().putIfAbsent(ORIGINAL_REQUEST_PATH_ATTRIBUTE, originalPath);

        String correlationId = resolveCorrelationId(exchange);

        ServerHttpRequest request = exchange.getRequest()
                                            .mutate()
                                            .headers(headers -> headers.set(HEADER_NAME, correlationId))
                                            .build();

        ServerWebExchange updatedExchange = exchange.mutate().request(request).build();

        updatedExchange.getAttributes().put(HEADER_NAME, correlationId);

        updatedExchange.getResponse().beforeCommit(() -> {
            updatedExchange.getResponse().getHeaders().set(HEADER_NAME, correlationId);

            return Mono.empty();
        });

        return chain.filter(updatedExchange);
    }

    private String resolveCorrelationId(ServerWebExchange exchange) {
        List<String> values = exchange.getRequest().getHeaders().get(HEADER_NAME);

        if (values != null && values.size() == 1) {
            String value = values.getFirst();

            if (value != null && VALID_ID.matcher(value).matches()) {
                return value;
            }
        }

        return UUID.randomUUID().toString();
    }
}
