package ru.ticketcraft.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RateLimitFilterMvcTest {

    private static final String CLIENT_IP = "203.0.113.10";

    private RedisRateLimiter rateLimiter;
    private SimpleMeterRegistry meterRegistry;
    private TestController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RedisRateLimiter.class);
        meterRegistry = new SimpleMeterRegistry();
        controller = new TestController();

        RateLimitProperties properties = new RateLimitProperties(
                true,
                new RateLimitProperties.Policy(
                        120,
                        120,
                        Duration.ofMinutes(1)),
                new RateLimitProperties.Policy(
                        20,
                        20,
                        Duration.ofMinutes(1)));

        RateLimitMetrics metrics = new RateLimitMetrics(meterRegistry);

        RateLimitFilter filter = new RateLimitFilter(
                properties,
                rateLimiter,
                new RemoteAddressClientKeyResolver(),
                metrics);

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .addFilters(filter)
                .build();
    }

    @Test
    void shouldAllowCatalogRequestBelowLimit() throws Exception {
        when(rateLimiter.acquire(
                RateLimitPolicy.CATALOG_READ,
                CLIENT_IP))
                .thenReturn(new RateLimitResult(
                        true,
                        120,
                        119,
                        Duration.ZERO));

        mockMvc.perform(get("/api/v1/catalog/events")
                        .with(request -> {
                            request.setRemoteAddr(CLIENT_IP);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        RateLimitFilter.HEADER_RATE_LIMIT_LIMIT,
                        "120"))
                .andExpect(header().string(
                        RateLimitFilter.HEADER_RATE_LIMIT_REMAINING,
                        "119"));

        verify(rateLimiter).acquire(
                RateLimitPolicy.CATALOG_READ,
                CLIENT_IP);

        assertThat(meterRegistry.counter(
                "ticketcraft.ratelimit.allowed",
                "policy",
                "catalog-read").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldReturn429WhenReservationLimitIsExceeded() throws Exception {
        UUID ticketId = UUID.randomUUID();

        when(rateLimiter.acquire(
                RateLimitPolicy.TICKET_RESERVATION,
                CLIENT_IP))
                .thenReturn(new RateLimitResult(
                        false,
                        20,
                        0,
                        Duration.ofMillis(1500)));

        mockMvc.perform(post(
                        "/api/v1/catalog/tickets/{ticketId}/reserve",
                        ticketId)
                        .with(request -> {
                            request.setRemoteAddr(CLIENT_IP);
                            return request;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(
                        RateLimitFilter.HEADER_RATE_LIMIT_LIMIT,
                        "20"))
                .andExpect(header().string(
                        RateLimitFilter.HEADER_RATE_LIMIT_REMAINING,
                        "0"))
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(
                                "\"title\":\"Rate limit exceeded\"")));

        assertThat(controller.reservationCalls.get()).isZero();

        assertThat(meterRegistry.counter(
                "ticketcraft.ratelimit.rejected",
                "policy",
                "ticket-reservation").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldFailOpenWhenRedisRateLimiterIsUnavailable() throws Exception {
        UUID ticketId = UUID.randomUUID();

        when(rateLimiter.acquire(
                RateLimitPolicy.TICKET_RESERVATION,
                CLIENT_IP))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));

        mockMvc.perform(post(
                        "/api/v1/catalog/tickets/{ticketId}/reserve",
                        ticketId)
                        .with(request -> {
                            request.setRemoteAddr(CLIENT_IP);
                            return request;
                        }))
                .andExpect(status().isOk());

        assertThat(controller.reservationCalls.get()).isEqualTo(1);

        assertThat(meterRegistry.counter(
                "ticketcraft.ratelimit.errors",
                "policy",
                "ticket-reservation").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldBypassActuatorHealthEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void shouldBypassDemoCatalogEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/events-lazy"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/catalog/events-optimized"))
                .andExpect(status().isOk());

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void shouldUseDifferentPoliciesForReadsAndReservations() throws Exception {
        UUID ticketId = UUID.randomUUID();

        when(rateLimiter.acquire(
                RateLimitPolicy.CATALOG_READ,
                CLIENT_IP))
                .thenReturn(new RateLimitResult(
                        true,
                        120,
                        119,
                        Duration.ZERO));

        when(rateLimiter.acquire(
                RateLimitPolicy.TICKET_RESERVATION,
                CLIENT_IP))
                .thenReturn(new RateLimitResult(
                        true,
                        20,
                        19,
                        Duration.ZERO));

        mockMvc.perform(get("/api/v1/catalog/events")
                        .with(request -> {
                            request.setRemoteAddr(CLIENT_IP);
                            return request;
                        }))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                        "/api/v1/catalog/tickets/{ticketId}/reserve",
                        ticketId)
                        .with(request -> {
                            request.setRemoteAddr(CLIENT_IP);
                            return request;
                        }))
                .andExpect(status().isOk());

        verify(rateLimiter).acquire(
                RateLimitPolicy.CATALOG_READ,
                CLIENT_IP);
        verify(rateLimiter).acquire(
                RateLimitPolicy.TICKET_RESERVATION,
                CLIENT_IP);
    }

    @Test
    void shouldBypassRateLimiterWhenDisabled() throws Exception {
        reset(rateLimiter);

        RateLimitProperties disabledProperties = new RateLimitProperties(
                false,
                new RateLimitProperties.Policy(
                        120,
                        120,
                        Duration.ofMinutes(1)),
                new RateLimitProperties.Policy(
                        20,
                        20,
                        Duration.ofMinutes(1)));

        RateLimitFilter disabledFilter = new RateLimitFilter(
                disabledProperties,
                rateLimiter,
                new RemoteAddressClientKeyResolver(),
                new RateLimitMetrics(meterRegistry));

        MockMvc disabledMockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .addFilters(disabledFilter)
                .build();

        disabledMockMvc.perform(get("/api/v1/catalog/events"))
                .andExpect(status().isOk());

        verifyNoInteractions(rateLimiter);
    }

    @RestController
    static class TestController {

        private final AtomicInteger reservationCalls = new AtomicInteger();

        @GetMapping("/api/v1/catalog/events")
        String events() {
            return "ok";
        }

        @GetMapping("/api/v1/catalog/events-lazy")
        String eventsLazy() {
            return "ok";
        }

        @GetMapping("/api/v1/catalog/events-optimized")
        String eventsOptimized() {
            return "ok";
        }

        @PostMapping("/api/v1/catalog/tickets/{ticketId}/reserve")
        String reserve(@PathVariable("ticketId") UUID ticketId) {
            reservationCalls.incrementAndGet();
            return ticketId.toString();
        }

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }
    }
}
