package ru.ticketcraft.payment.gateway;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

@Component
public class FakePaymentGateway implements PaymentGateway {

    @Override
    public PaymentResult charge(Long orderId, Long userId, BigDecimal amount) {
        return PaymentResult.success();
    }
}
