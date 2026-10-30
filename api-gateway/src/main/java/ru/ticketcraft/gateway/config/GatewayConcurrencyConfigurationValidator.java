package ru.ticketcraft.gateway.config;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Component;

/**
 * Проверяет соответствие маршрутов из конфигурации и лимитов параллельности.
 *
 * <p>
 * Завершает запуск приложения с ошибкой, если для маршрута отсутствует лимит
 * или лимит ссылается на неизвестный маршрут.
 *
 * <p>
 * Маршруты, объявленные программно, в эту проверку не входят.
 */
@Component
public class GatewayConcurrencyConfigurationValidator {

    public GatewayConcurrencyConfigurationValidator(GatewayProperties gatewayProperties,
                                                    GatewayConcurrencyProperties concurrencyProperties) {
        validate(gatewayProperties, concurrencyProperties);
    }

    private void validate(GatewayProperties gatewayProperties,
                          GatewayConcurrencyProperties concurrencyProperties) {
        Set<String> routeIds = new HashSet<>();

        for (RouteDefinition route : gatewayProperties.getRoutes()) {
            String routeId = route.getId();

            if (routeId == null || routeId.isBlank()) {
                throw new IllegalStateException("Gateway route ID must not be blank");
            }

            if (!routeIds.add(routeId)) {
                throw new IllegalStateException("Duplicate Gateway route ID: " + routeId);
            }
        }

        Set<String> missingLimits = new TreeSet<>(routeIds);
        missingLimits.removeAll(concurrencyProperties.limits().keySet());

        Set<String> unknownRoutes = new TreeSet<>(concurrencyProperties.limits().keySet());
        unknownRoutes.removeAll(routeIds);

        if (!missingLimits.isEmpty() || !unknownRoutes.isEmpty()) {
            throw new IllegalStateException("Invalid Gateway concurrency configuration: routes without limits="
                    + missingLimits + "; limits for unknown routes=" + unknownRoutes);
        }
    }
}