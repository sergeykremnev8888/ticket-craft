package ru.ticketcraft.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {

    /**
     * Charges the payment using paymentId as the idempotency key. Repeated calls
     * with the same paymentId must represent the same payment operation.
     */
    PaymentResult charge(UUID paymentId, Long orderId, Long userId, BigDecimal amount);
}
