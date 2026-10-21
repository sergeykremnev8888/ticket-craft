package ru.ticketcraft.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;

/**
 * Конфигурация диагностических маршрутов API Gateway для локальной разработки.
 *
 * <p>Активируется только при включённом профиле {@code local}.
 * Добавляет маршрут {@code GET /debug/slow} в Catalog Service
 * для проверки таймаутов и поведения Circuit Breaker.
 *
 * <p>Маршрут использует отдельный Circuit Breaker
 * {@code catalogDebugCircuitBreaker}, чтобы диагностические запросы
 * не влияли на состояние Circuit Breaker основных маршрутов каталога.
 *
 * <p>При отказе выполняется внутреннее перенаправление
 * на {@code /fallback/catalog}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalDebugRoutesConfig {

    @Bean
    RouteLocator localDebugRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("catalog-debug", route -> route
                        .path("/debug/slow")
                        .and()
                        .method("GET")
                        .filters(filters -> filters
                                .circuitBreaker(config -> config
                                        .setName("catalogDebugCircuitBreaker")
                                        .addStatusCode(HttpStatus.SERVICE_UNAVAILABLE.name())
                                        .setFallbackUri("forward:/fallback/catalog")))
                        .uri("http://localhost:8081"))
                .build();
    }
}
