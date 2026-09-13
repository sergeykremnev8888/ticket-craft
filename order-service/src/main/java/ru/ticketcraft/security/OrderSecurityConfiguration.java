package ru.ticketcraft.security;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class OrderSecurityConfiguration {

    private static final String ORDERS_WRITE_AUTHORITY = "SCOPE_orders.write";

    private static final String METRICS_READ_AUTHORITY = "SCOPE_metrics.read";

    private static final BearerTokenAuthenticationEntryPoint AUTHENTICATION_ENTRY_POINT =
            new BearerTokenAuthenticationEntryPoint();

    private static final BearerTokenAccessDeniedHandler ACCESS_DENIED_HANDLER = new BearerTokenAccessDeniedHandler();

    @Bean
    SecurityFilterChain orderSecurityFilterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAuthority(METRICS_READ_AUTHORITY)
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasAuthority(ORDERS_WRITE_AUTHORITY)
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(OrderSecurityConfiguration::handleUnauthorized)
                        .accessDeniedHandler(OrderSecurityConfiguration::handleForbidden))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(OrderSecurityConfiguration::handleUnauthorized)
                        .accessDeniedHandler(OrderSecurityConfiguration::handleForbidden)
                        .jwt(Customizer.withDefaults()));

        return http.build();
    }

    private static void handleUnauthorized(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {

        AUTHENTICATION_ENTRY_POINT.commence(request, response, exception);
        writeProblem(response, HttpStatus.UNAUTHORIZED, "Unauthorized", "A valid Bearer token is required");
    }

    private static void handleForbidden(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException, ServletException {

        ACCESS_DENIED_HANDLER.handle(request, response, exception);
        writeProblem(response, HttpStatus.FORBIDDEN, "Forbidden",
                "The authenticated principal does not have permission to access this resource");
    }

    private static void writeProblem(HttpServletResponse response, HttpStatus status, String title, String detail)
            throws IOException {

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":"
                + status.value() + ",\"detail\":\"" + detail + "\"}");
    }
}
