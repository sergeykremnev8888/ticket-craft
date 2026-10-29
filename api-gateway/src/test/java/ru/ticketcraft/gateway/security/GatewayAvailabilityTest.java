package ru.ticketcraft.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class GatewayAvailabilityTest {

    @Autowired
    private WebTestClient client;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @Test
    void shouldExposeProbesWithoutAuthentication() {
        client.get()
              .uri("/actuator/health/liveness")
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody()
              .jsonPath("$.status")
              .isEqualTo("UP")
              .jsonPath("$.components")
              .doesNotExist();

        client.get()
              .uri("/actuator/health/readiness")
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody()
              .jsonPath("$.status")
              .isEqualTo("UP");
    }

    @Test
    void shouldReportNotReadyWithoutBreakingLiveness() {
        try {
            AvailabilityChangeEvent.publish(context, this, ReadinessState.REFUSING_TRAFFIC);

            client.get()
                  .uri("/actuator/health/readiness")
                  .exchange()
                  .expectStatus()
                  .isEqualTo(503)
                  .expectBody()
                  .jsonPath("$.status")
                  .isEqualTo("OUT_OF_SERVICE");

            client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        } finally {
            AvailabilityChangeEvent.publish(context, this, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @Test
    void shouldReportBrokenLiveness() {
        try {
            AvailabilityChangeEvent.publish(context, this, LivenessState.BROKEN);

            client.get()
                  .uri("/actuator/health/liveness")
                  .exchange()
                  .expectStatus()
                  .isEqualTo(503)
                  .expectBody()
                  .jsonPath("$.status")
                  .isEqualTo("DOWN");
        } finally {
            AvailabilityChangeEvent.publish(context, this, LivenessState.CORRECT);
        }
    }
}