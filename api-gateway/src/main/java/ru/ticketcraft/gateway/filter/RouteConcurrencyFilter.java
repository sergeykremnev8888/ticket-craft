package ru.ticketcraft.gateway.filter;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;
import ru.ticketcraft.gateway.config.GatewayConcurrencyProperties;
import ru.ticketcraft.gateway.exception.GatewayProblemWriter;

/**
 * Ограничивает число активных запросов на маршрут. Не блокирует поток и не
 * создаёт очередь ожидания.
 */
@Component
@Order(-200)
@EnableConfigurationProperties(GatewayConcurrencyProperties.class)
public class RouteConcurrencyFilter implements GlobalFilter {

    private final Map<String, Semaphore> semaphores;
    private final Map<String, Counter> rejectionCounters;
    private final GatewayProblemWriter problemWriter;

    public RouteConcurrencyFilter(GatewayConcurrencyProperties properties,
                                  GatewayProblemWriter problemWriter,
                                  MeterRegistry meterRegistry) {
        this.problemWriter = problemWriter;

        this.semaphores = 
                properties.limits().entrySet().stream()
                          .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                                entry -> new Semaphore(entry.getValue())));

        this.rejectionCounters = 
                properties.limits().keySet().stream()
                          .collect(Collectors.toUnmodifiableMap(routeId -> routeId,
                                                                routeId -> Counter.builder("ticketcraft.gateway.concurrency.rejected")
                                                                                  .description("Requests rejected by route concurrency limit")
                                                                                  .tag("route", routeId)
                                                                                  .register(meterRegistry)));

        properties.limits()
                  .forEach((routeId, limit) -> Gauge.builder("ticketcraft.gateway.concurrency.active",
                                                             semaphores.get(routeId),
                                                             semaphore -> limit - semaphore.availablePermits())
                                                    .description("Requests holding a route concurrency permit")
                                                    .tag("route", routeId)
                                                    .register(meterRegistry));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        Semaphore semaphore = route == null ? null : semaphores.get(route.getId());
        if (semaphore == null) {
            // Маршруты без настройки не ограничиваются этим фильтром.
            return chain.filter(exchange);
        }

        return Mono.using(semaphore::tryAcquire, 
                          acquired -> {
                              if (!acquired) {
                                  rejectionCounters.get(route.getId()).increment();
                                  return rejectOverloaded(exchange);
                              }
                              return chain.filter(exchange);
                          },
                          acquired -> {
                              if (acquired) {
                                  semaphore.release();
                              }
                          },
                          true);
    }

    private Mono<Void> rejectOverloaded(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set("Retry-After", "1");
        return problemWriter.write(exchange,
                                   HttpStatus.SERVICE_UNAVAILABLE,
                                   "GATEWAY_OVERLOADED",
                                   "Too many concurrent requests. Retry later.");
    }
}