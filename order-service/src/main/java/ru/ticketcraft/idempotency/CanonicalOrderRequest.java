package ru.ticketcraft.idempotency;

import java.util.UUID;

public record CanonicalOrderRequest(Long userId, UUID ticketId) {

    public String serialize() {
        return userId + "|" + ticketId;
    }
}
