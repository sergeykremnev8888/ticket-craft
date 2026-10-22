package ru.ticketcraft.gateway.exception;

import java.net.URI;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import ru.ticketcraft.gateway.filter.CorrelationIdFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Формирует и записывает ошибки API Gateway в формате application/problem+json.
 *
 * <p>
 * Добавляет код ошибки и идентификатор корреляции. Существующие заголовки
 * ответа, включая WWW-Authenticate, сохраняются.
 */
@Component
public class GatewayProblemWriter {

    private final JsonMapper jsonMapper;

    public GatewayProblemWriter(JsonMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "'jsonMapper' is null");
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        URI instance = URI.create(exchange.getRequest().getURI().getRawPath());
        return write(exchange, status, code, detail, instance);
    }

    /**
     * Записывает ошибку с явно указанным исходным URI запроса.
     *
     * <p>
     * Используется, в частности, при внутреннем перенаправлении на fallback, когда
     * текущий путь запроса уже изменён.
     */
    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String detail, URI instance) {
        return Mono.defer(() -> {
            ServerHttpResponse response = exchange.getResponse();

            if (response.isCommitted()) {
                return Mono.error(new IllegalStateException("Cannot write problem: response is already committed"));
            }

            ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
            problem.setType(URI.create("about:blank"));
            problem.setInstance(instance);
            problem.setProperty("code", code);

            String correlationId = exchange.getAttribute(CorrelationIdFilter.HEADER_NAME);
            if (correlationId != null) {
                problem.setProperty("correlationId", correlationId);
            }

            byte[] body = jsonMapper.writeValueAsBytes(problem);

            response.setStatusCode(status);
            response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            response.getHeaders().setContentLength(body.length);

            return response.writeWith(Mono.fromSupplier(() -> response.bufferFactory().wrap(body)));
        });
    }
}