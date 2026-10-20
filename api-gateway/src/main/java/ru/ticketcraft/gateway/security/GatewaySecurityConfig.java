package ru.ticketcraft.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(
                                HttpMethod.GET,
                                "/api/v1/catalog/events",
                                "/api/v1/catalog/events/**"
                        ).permitAll()

                        .pathMatchers(HttpMethod.POST, "/api/v1/orders"
                        ).permitAll()

                        .pathMatchers(HttpMethod.GET, "/api/v1/orders/**")
                        .hasAuthority("SCOPE_orders.read")

                        .anyExchange().denyAll()
                )
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(Customizer.withDefaults())
                )
                .build();
    }

}
