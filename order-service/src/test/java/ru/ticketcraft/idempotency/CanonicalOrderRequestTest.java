package ru.ticketcraft.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CanonicalOrderRequestTest {

    @Test
    void shouldGenerateDeterministicSerialization() {
        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID ticketId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        CanonicalOrderRequest request = new CanonicalOrderRequest(100L, eventId, ticketId, new BigDecimal("100.00"));

        assertEquals("100|11111111-1111-1111-1111-111111111111" + "|22222222-2222-2222-2222-222222222222" + "|100",
                request.serialize());
    }

    @Test
    void shouldNormalizePriceScale() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();

        CanonicalOrderRequest request1 = new CanonicalOrderRequest(100L, eventId, ticketId, new BigDecimal("100.00"));

        CanonicalOrderRequest request2 = new CanonicalOrderRequest(100L, eventId, ticketId, new BigDecimal("100.0"));

        assertEquals(request1.serialize(), request2.serialize());

        assertEquals("100|" + eventId + "|" + ticketId + "|100", request1.serialize());
    }
}
