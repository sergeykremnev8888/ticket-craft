package ru.ticketcraft.security;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.ticketcraft.controller.OrderController;
import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.service.OrderService;

@WebMvcTest(
        controllers = { 
                OrderController.class,
                OrderSecurityConfigurationTest.ActuatorTestController.class
        }
)
@Import({ 
    OrderSecurityConfiguration.class,
    JwtUserIdResolver.class,
    OrderSecurityConfigurationTest.ActuatorTestController.class
})
class OrderSecurityConfigurationTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final BigDecimal PRICE = new BigDecimal("150.00");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldRequireAuthenticationForOrderCreation() throws Exception {

        mockMvc.perform(post("/api/v1/orders").header("Idempotency-Key", "idem-1")
                .contentType(MediaType.APPLICATION_JSON).content(orderRequest())).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(orderService);
    }

    @Test
    void shouldRequireOrdersWriteScope() throws Exception {

        mockMvc.perform(post("/api/v1/orders").with(jwt().jwt(token -> token.claim("user_id", 42L)))
                .header("Idempotency-Key", "idem-1").contentType(MediaType.APPLICATION_JSON).content(orderRequest()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(orderService);
    }

    @Test
    void shouldTakeUserIdFromJwtAndCreateOrder() throws Exception {

        Order order = new Order(10L, 42L, EVENT_ID, TICKET_ID, PRICE, OrderState.CREATED, Instant.now());

        when(orderService.createOrder(eq("idem-1"), eq(42L), eq(EVENT_ID), eq(TICKET_ID), eq(PRICE))).thenReturn(order);

        mockMvc.perform(post("/api/v1/orders")
                .with(jwt().jwt(token -> token.claim("user_id", 42L))
                        .authorities(new SimpleGrantedAuthority("SCOPE_orders.write")))
                .header("Idempotency-Key", "idem-1").contentType(MediaType.APPLICATION_JSON).content(orderRequest()))
                .andExpect(status().isCreated());

        verify(orderService).createOrder("idem-1", 42L, EVENT_ID, TICKET_ID, PRICE);
    }

    @Test
    void shouldRejectTokenWithoutUserIdClaim() throws Exception {

        mockMvc.perform(post("/api/v1/orders").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_orders.write")))
                .header("Idempotency-Key", "idem-1").contentType(MediaType.APPLICATION_JSON).content(orderRequest()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(orderService);
    }

    @Test
    void shouldProtectPrometheusWithMetricsScope() throws Exception {

        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());

        mockMvc.perform(
                get("/actuator/prometheus").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_metrics.read"))))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowHealthWithoutAuthentication() throws Exception {

        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private static String orderRequest() {

        return """
                {
                  "eventId": "%s",
                  "ticketId": "%s",
                  "price": %s
                }
                """.formatted(EVENT_ID, TICKET_ID, PRICE);
    }

    @RestController
    static class ActuatorTestController {

        @GetMapping("/actuator/prometheus")
        String prometheus() {
            return "metrics";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }
    }
}