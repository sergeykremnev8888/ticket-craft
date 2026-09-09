package ru.ticketcraft.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PaymentEventsJacksonTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void shouldSerializeAndDeserializePaymentRequestedEvent() throws Exception {
        PaymentRequestedEvent source = new PaymentRequestedEvent("message-1", 100L, 200L, new BigDecimal("125.50"),
                Instant.parse("2026-09-09T10:15:30Z"));

        String json = objectMapper.writeValueAsString(source);

        PaymentRequestedEvent result = objectMapper.readValue(json, PaymentRequestedEvent.class);

        assertThat(result).isEqualTo(source);
    }

    @Test
    void shouldSerializeAndDeserializePaymentSucceededEvent() throws Exception {
        PaymentSucceededEvent source = new PaymentSucceededEvent("message-2", 100L,
                UUID.fromString("11111111-1111-1111-1111-111111111111"), new BigDecimal("125.50"),
                Instant.parse("2026-09-09T10:16:30Z"));

        String json = objectMapper.writeValueAsString(source);

        PaymentSucceededEvent result = objectMapper.readValue(json, PaymentSucceededEvent.class);

        assertThat(result).isEqualTo(source);
    }

    @Test
    void shouldSerializeAndDeserializePaymentFailedEvent() throws Exception {   
        PaymentFailedEvent source = new PaymentFailedEvent("message-3", 100L,
                UUID.fromString("22222222-2222-2222-2222-222222222222"), new BigDecimal("125.50"), "Insufficient funds",
                Instant.parse("2026-09-09T10:17:30Z"));

        String json = objectMapper.writeValueAsString(source);

        PaymentFailedEvent result = objectMapper.readValue(json, PaymentFailedEvent.class);

        assertThat(result).isEqualTo(source);
    }

    @Test
    void shouldSerializePaymentRequestedEventWithExpectedFields() throws Exception {
        PaymentRequestedEvent event = new PaymentRequestedEvent("message-1", 100L, 200L, new BigDecimal("125.50"),
                Instant.parse("2026-09-09T10:15:30Z"));

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"messageId\":\"message-1\"").contains("\"orderId\":100").contains("\"userId\":200")
                .contains("\"amount\":125.50").contains("\"createdAt\":\"2026-09-09T10:15:30Z\"");
    }
}