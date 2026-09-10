package ru.ticketcraft.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {

    /**
     * Executes payment using paymentId as an idempotency key.
     *
     * Repeated calls with the same paymentId must not result in multiple charges.
     */
    PaymentResult charge(UUID paymentId, Long orderId, Long userId, BigDecimal amount);

}
