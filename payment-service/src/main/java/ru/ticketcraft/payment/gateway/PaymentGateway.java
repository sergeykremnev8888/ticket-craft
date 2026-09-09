package ru.ticketcraft.payment.gateway;

import java.math.BigDecimal;

public interface PaymentGateway {

    PaymentResult charge(Long orderId, Long userId, BigDecimal amount);

}
