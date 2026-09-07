package ru.ticketcraft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.ticketcraft.idempotency.CanonicalOrderRequest;

class RequestHashServiceTest {

    private final RequestHashService service = new RequestHashService();

    @Test
    void shouldGenerateSameHashForEquivalentPrices() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();

        CanonicalOrderRequest request1 = new CanonicalOrderRequest(100L, eventId, ticketId, new BigDecimal("100.00"));

        CanonicalOrderRequest request2 = new CanonicalOrderRequest(100L, eventId, ticketId, new BigDecimal("100.0"));

        assertEquals(service.hash(request1), service.hash(request2));
    }

    @Test
    void shouldGenerateDifferentHashForDifferentUser() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();

        CanonicalOrderRequest request1 = new CanonicalOrderRequest(100L, eventId, ticketId, new BigDecimal("100.00"));

        CanonicalOrderRequest request2 = new CanonicalOrderRequest(101L, eventId, ticketId, new BigDecimal("100.00"));

        assertNotEquals(service.hash(request1), service.hash(request2));
    }

    @Test
    void shouldGenerateDifferentHashForDifferentEvent() {
        UUID ticketId = UUID.randomUUID();

        CanonicalOrderRequest request1 = new CanonicalOrderRequest(100L, UUID.randomUUID(), ticketId,
                new BigDecimal("100.00"));

        CanonicalOrderRequest request2 = new CanonicalOrderRequest(100L, UUID.randomUUID(), ticketId,
                new BigDecimal("100.00"));

        assertNotEquals(service.hash(request1), service.hash(request2));
    }

    @Test
    void shouldGenerateDifferentHashForDifferentTicket() {
        UUID eventId = UUID.randomUUID();

        CanonicalOrderRequest request1 = new CanonicalOrderRequest(100L, eventId, UUID.randomUUID(),
                new BigDecimal("100.00"));

        CanonicalOrderRequest request2 = new CanonicalOrderRequest(100L, eventId, UUID.randomUUID(),
                new BigDecimal("100.00"));

        assertNotEquals(service.hash(request1), service.hash(request2));
    }

    @Test
    void shouldGenerateDifferentHashForDifferentPrice() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();

        CanonicalOrderRequest request1 = new CanonicalOrderRequest(100L, eventId, ticketId, new BigDecimal("100.00"));

        CanonicalOrderRequest request2 = new CanonicalOrderRequest(100L, eventId, ticketId, new BigDecimal("200.00"));

        assertNotEquals(service.hash(request1), service.hash(request2));
    }

    @Test
    void shouldGenerateDeterministicHash() {
        CanonicalOrderRequest request = new CanonicalOrderRequest(100L, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.00"));

        String hash1 = service.hash(request);
        String hash2 = service.hash(request);

        assertEquals(hash1, hash2);
    }

    @Test
    void shouldGenerate64CharacterLowercaseHexHash() {
        CanonicalOrderRequest request = new CanonicalOrderRequest(100L, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.00"));

        String hash = service.hash(request);

        assertNotNull(hash);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }
}
