package ru.ticketcraft.ratelimit;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = RateLimitFilterMvcTest.TestController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class,
                OAuth2ResourceServerWebSecurityAutoConfiguration.class
        }
)
@Import({RateLimitFilter.class, RateLimitFilterMvcTest.TestController.class})
class RateLimitFilterMvcTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private RedisRateLimiter rateLimiter;
    @MockitoBean private ClientKeyResolver clientKeyResolver;
    @MockitoBean private RateLimitProperties properties;
    @MockitoBean private RateLimitMetrics metrics;

    @Test
    void shouldApplyCatalogReadLimit() throws Exception {
        when(properties.enabled()).thenReturn(true);
        when(clientKeyResolver.resolve(org.mockito.ArgumentMatchers.any())).thenReturn("client");
        when(rateLimiter.acquire(eq(RateLimitPolicy.CATALOG_READ), eq("client")))
                .thenReturn(new RateLimitResult(true, 120, 119, Duration.ZERO));

        mockMvc.perform(get("/api/v1/catalog/events"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "120"))
                .andExpect(header().string("X-RateLimit-Remaining", "119"));
    }

    @RestController
    static class TestController {
        @GetMapping("/api/v1/catalog/events")
        String events() { return "ok"; }
    }
}
