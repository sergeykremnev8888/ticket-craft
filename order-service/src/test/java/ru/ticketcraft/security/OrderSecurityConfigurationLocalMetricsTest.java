package ru.ticketcraft.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = OrderSecurityConfigurationLocalMetricsTest.ActuatorTestController.class)
@Import({ OrderSecurityConfiguration.class, OrderSecurityConfigurationLocalMetricsTest.ActuatorTestController.class })
@TestPropertySource(properties = "ticketcraft.security.metrics-public=true")
class OrderSecurityConfigurationLocalMetricsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldAllowPrometheusWithoutAuthenticationWhenMetricsArePublic() throws Exception {

        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @RestController
    static class ActuatorTestController {

        @GetMapping("/actuator/prometheus")
        String prometheus() {
            return "metrics";
        }
    }
}