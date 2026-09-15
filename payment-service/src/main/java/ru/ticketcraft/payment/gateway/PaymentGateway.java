package ru.ticketcraft.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {

    /**
     * Charges the payment using paymentId as the idempotency key.
     */
    PaymentResult charge(UUID paymentId, Long orderId, Long userId, BigDecimal amount);

    /**
     * Refunds an already successful payment.
     *
     * Repeated calls for the same paymentId must represent the same refund
     * operation.
     */
    PaymentResult refund(UUID paymentId, Long orderId, BigDecimal amount);
}