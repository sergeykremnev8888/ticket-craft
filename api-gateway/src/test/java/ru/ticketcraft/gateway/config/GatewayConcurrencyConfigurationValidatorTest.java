package ru.ticketcraft.gateway.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;

class GatewayConcurrencyConfigurationValidatorTest {

    @Test
    void shouldAcceptLimitsForAllConfiguredRoutes() {
        assertDoesNotThrow(() -> validator(Map.of("catalog", 64, "order", 32), "catalog", "order"));
    }

    @Test
    void shouldRejectRouteWithoutLimit() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                                                       () -> validator(Map.of("catalog", 64), "catalog", "order"));

        assertTrue(exception.getMessage().contains("routes without limits=[order]"));
    }

    @Test
    void shouldRejectLimitForUnknownRoute() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                                                       () -> validator(Map.of("catalog", 64, "order", 32), "catalog"));

        assertTrue(exception.getMessage().contains("limits for unknown routes=[order]"));
    }

    @Test
    void shouldReportBothSidesOfRouteIdTypo() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                                                       () -> validator(Map.of("catalog", 64, "oder", 32),
                                                                       "catalog",
                                                                       "order"));

        assertTrue(exception.getMessage().contains("routes without limits=[order]"));
        assertTrue(exception.getMessage().contains("limits for unknown routes=[oder]"));
    }

    @Test
    void shouldRejectDuplicateRouteIds() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                                                       () -> validator(Map.of("catalog", 64), "catalog", "catalog"));

        assertTrue(exception.getMessage().contains("Duplicate Gateway route ID: catalog"));
    }

    private GatewayConcurrencyConfigurationValidator validator(Map<String, Integer> limits, String... routeIds) {
        GatewayProperties gatewayProperties = new GatewayProperties();
        gatewayProperties.setRoutes(Arrays.stream(routeIds).map(this::route).toList());
        return new GatewayConcurrencyConfigurationValidator(gatewayProperties,
                new GatewayConcurrencyProperties(limits));
    }

    private RouteDefinition route(String routeId) {
        RouteDefinition route = new RouteDefinition();
        route.setId(routeId);
        route.setUri(URI.create("http://localhost:8081"));
        return route;
    }
}