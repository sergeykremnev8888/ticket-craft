package ru.ticketcraft.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {

    PaymentResult charge(UUID paymentId, Long orderId, Long userId, BigDecimal amount);

}
