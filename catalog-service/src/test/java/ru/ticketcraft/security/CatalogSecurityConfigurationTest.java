package ru.ticketcraft.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.ticketcraft.ratelimit.RateLimitFilter;

@WebMvcTest(controllers = CatalogSecurityConfigurationTest.TestController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RateLimitFilter.class))
@Import({CatalogSecurityConfiguration.class, CatalogSecurityConfigurationTest.TestController.class})
@TestPropertySource(properties = "ticketcraft.security.metrics-public=false")
class CatalogSecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldAllowPublicCatalogReadsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/events")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/catalog/events/11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk());
    }

    @RestController
    static class TestController {
        @GetMapping("/api/v1/catalog/events")
        ResponseEntity<Void> events() { return ResponseEntity.ok().build(); }

        @GetMapping("/api/v1/catalog/events/{eventId}")
        ResponseEntity<Void> event() { return ResponseEntity.ok().build(); }
    }
}
