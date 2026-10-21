package ru.ticketcraft.gateway.security;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(GatewayCorsProperties.class)
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable).cors(Customizer.withDefaults())
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.GET, "/api/v1/catalog/events", "/api/v1/catalog/events/**").permitAll()

                        .pathMatchers(HttpMethod.POST, "/api/v1/orders").permitAll()
                        .pathMatchers(HttpMethod.GET, "/debug/slow").permitAll()
                        .pathMatchers("/fallback/**").permitAll()
                        .pathMatchers(HttpMethod.GET, 
                                "/actuator/health",
                                "/actuator/prometheus").permitAll()

                        .pathMatchers(HttpMethod.GET, "/api/v1/orders/**").hasAuthority("SCOPE_orders.read")

                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())).build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayCorsProperties сorsProperties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(сorsProperties.allowedOrigins());
        config.setAllowedMethods(сorsProperties.allowedMethods());
        config.setAllowedHeaders(сorsProperties.allowedHeaders());
        config.setExposedHeaders(сorsProperties.exposedHeaders());
        config.setMaxAge(сorsProperties.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return source;
    }

}
