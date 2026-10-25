package ru.ticketcraft.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.ticketcraft.order.idempotency.CanonicalOrderRequest;
import ru.ticketcraft.order.service.RequestHashService;

class RequestHashServiceTest {

    private final RequestHashService service = new RequestHashService();

    @Test
    void shouldReturnSameHashForSameRequest() {
        UUID ticketId = UUID.randomUUID();
        CanonicalOrderRequest first = new CanonicalOrderRequest(100L, ticketId);
        CanonicalOrderRequest second = new CanonicalOrderRequest(100L, ticketId);

        assertThat(service.hash(first)).isEqualTo(service.hash(second));
    }

    @Test
    void shouldReturnDifferentHashForDifferentUserOrTicket() {
        UUID ticketId = UUID.randomUUID();

        assertThat(service.hash(new CanonicalOrderRequest(100L, ticketId)))
                .isNotEqualTo(service.hash(new CanonicalOrderRequest(101L, ticketId)));
        assertThat(service.hash(new CanonicalOrderRequest(100L, ticketId)))
                .isNotEqualTo(service.hash(new CanonicalOrderRequest(100L, UUID.randomUUID())));
    }
}
