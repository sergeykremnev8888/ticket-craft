package ru.ticketcraft.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.JwkSetUriReactiveJwtDecoderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;
import ru.ticketcraft.gateway.exception.JwkEndpointUnavailableException;

/**
 * Ограничивает ожидание публичных ключей и выделяет ошибки их загрузки.
 *
 * <p>
 * Сохраняет стандартную автоконфигурацию JWT decoder и валидаторов.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayJwkProperties.class)
public class GatewayJwkConfiguration {

    @Bean
    JwkSetUriReactiveJwtDecoderBuilderCustomizer jwkHttpClientCustomizer(GatewayJwkProperties properties) {
        WebClient webClient = WebClient.builder().filter(jwkRequestFilter(properties)).build();
        return builder -> builder.webClient(webClient);
    }

    private ExchangeFilterFunction jwkRequestFilter(GatewayJwkProperties properties) {
        return (request, next) -> next.exchange(request)
                                      .flatMap(this::readResponse)
                                      .timeout(properties.requestTimeout())
                                      .onErrorMap(error -> !(error instanceof JwkEndpointUnavailableException),
                                                  JwkEndpointUnavailableException::new);
    }

    private Mono<ClientResponse> readResponse(ClientResponse response) {
        if (!response.statusCode().is2xxSuccessful()) {
            JwkEndpointUnavailableException exception = new JwkEndpointUnavailableException(
                    new IllegalStateException("Unexpected JWK HTTP status: " + response.statusCode().value()));

            return response.releaseBody().then(Mono.error(exception));
        }

        // Читаем тело внутри ограниченного таймаутом участка.
        // Затем восстанавливаем его для стандартного JWT decoder.
        return response.bodyToMono(String.class).defaultIfEmpty("").map(body -> response.mutate().body(body).build());
    }
}