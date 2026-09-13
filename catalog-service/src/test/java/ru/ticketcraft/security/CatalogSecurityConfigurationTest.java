package ru.ticketcraft.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.ticketcraft.ratelimit.RateLimitFilter;

@WebMvcTest(
        controllers = CatalogSecurityConfigurationTest.TestController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = RateLimitFilter.class
        ))
@Import({
        CatalogSecurityConfiguration.class,
        CatalogSecurityConfigurationTest.TestController.class
})
class CatalogSecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldAllowPublicCatalogReadsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/events"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRequireAuthenticationForDirectReservation() throws Exception {
        mockMvc.perform(
                        post("/api/v1/catalog/tickets/00000000-0000-0000-0000-000000000001/reserve")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectReservationWithoutCatalogWriteScope() throws Exception {
        mockMvc.perform(
                        post("/api/v1/catalog/tickets/00000000-0000-0000-0000-000000000001/reserve")
                                .with(jwt())
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowReservationWithCatalogWriteScope() throws Exception {
        mockMvc.perform(
                        post("/api/v1/catalog/tickets/00000000-0000-0000-0000-000000000001/reserve")
                                .with(jwt().authorities(() -> "SCOPE_catalog.write"))
                )
                .andExpect(status().isOk());
    }

    @RestController
    static class TestController {

        @GetMapping("/api/v1/catalog/events")
        ResponseEntity<Void> catalog() {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/api/v1/catalog/tickets/{ticketId}/reserve")
        ResponseEntity<Void> reserve() {
            return ResponseEntity.ok().build();
        }
    }
}