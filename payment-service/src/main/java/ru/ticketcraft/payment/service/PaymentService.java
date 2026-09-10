package ru.ticketcraft.payment.service;

import org.springframework.stereotype.Service;

import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.payment.gateway.PaymentGateway;
import ru.ticketcraft.payment.gateway.PaymentResult;
import ru.ticketcraft.payment.model.Payment;
import ru.ticketcraft.payment.model.PaymentStatus;

@Service
public class PaymentService {

    private final PaymentTransactionService paymentTransactionService;
    private final PaymentGateway paymentGateway;

    public PaymentService(PaymentTransactionService paymentTransactionService, PaymentGateway paymentGateway) {
        this.paymentTransactionService = paymentTransactionService;
        this.paymentGateway = paymentGateway;
    }

    public PaymentResult process(PaymentRequestedEvent event) {
        Payment payment = paymentTransactionService.getOrCreatePayment(event);

        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            return PaymentResult.success();
        }

        if (payment.getStatus() == PaymentStatus.FAILED) {
            return PaymentResult.failure("Payment already failed");
        }

        PaymentResult result = paymentGateway.charge(payment.getId(), payment.getOrderId(), payment.getUserId(),
                payment.getAmount());

        PaymentStatus status = result.successful() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;

        paymentTransactionService.updatePaymentStatus(payment.getId(), status);

        return result;
    }

}
