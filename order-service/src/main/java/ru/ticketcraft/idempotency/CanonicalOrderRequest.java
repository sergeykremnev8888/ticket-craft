package ru.ticketcraft.idempotency;

import java.math.BigDecimal;
import java.util.UUID;

public record CanonicalOrderRequest(Long userId, UUID eventId, UUID ticketId, BigDecimal price) {

    public String serialize() {
        return String.join("|", String.valueOf(userId), eventId.toString(), ticketId.toString(),
                price.stripTrailingZeros().toPlainString());
    }
}
