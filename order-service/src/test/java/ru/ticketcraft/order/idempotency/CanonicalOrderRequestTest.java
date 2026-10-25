package ru.ticketcraft.order.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.ticketcraft.order.idempotency.CanonicalOrderRequest;

class CanonicalOrderRequestTest {

    @Test
    void shouldSerializeDeterministically() {
        UUID ticketId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        CanonicalOrderRequest request = new CanonicalOrderRequest(100L, ticketId);

        assertThat(request.serialize()).isEqualTo("100|22222222-2222-2222-2222-222222222222");
    }
}
