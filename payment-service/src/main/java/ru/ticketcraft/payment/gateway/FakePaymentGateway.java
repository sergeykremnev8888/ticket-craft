package ru.ticketcraft.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class FakePaymentGateway implements PaymentGateway {

    @Override
    public PaymentResult charge(UUID paymentId, Long orderId, Long userId, BigDecimal amount) {
        return PaymentResult.success();
    }
}
