package ru.ticketcraft.gateway.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
        Environment environment) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchange -> {
                    if (environment.acceptsProfiles(Profiles.of("local"))) {
                        exchange.pathMatchers(HttpMethod.GET, "/debug/slow").permitAll();
                    }

                    exchange.pathMatchers(HttpMethod.GET,
                                    "/api/v1/catalog/events",
                                    "/api/v1/catalog/events/**").permitAll()

                            .pathMatchers(HttpMethod.POST, "/api/v1/orders").authenticated()
                            .pathMatchers(HttpMethod.GET, "/api/v1/orders/**").authenticated()

                            .pathMatchers("/fallback/**").permitAll()

                            .pathMatchers(HttpMethod.GET,
                                    "/actuator/health",
                                    "/actuator/prometheus").permitAll()

                            .anyExchange().denyAll();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())).build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayCorsProperties corsProperties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.allowedOrigins());
        config.setAllowedMethods(corsProperties.allowedMethods());
        config.setAllowedHeaders(corsProperties.allowedHeaders());
        config.setExposedHeaders(corsProperties.exposedHeaders());
        config.setMaxAge(corsProperties.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return source;
    }

}
