package ru.ticketcraft.payment.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
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

        if (result.successful()) {
            PaymentSucceededEvent suceededEvent = new PaymentSucceededEvent(createSucceededMessageId(payment.getId()),
                    payment.getOrderId(), payment.getId(), payment.getAmount(), Instant.now());

            paymentTransactionService.markSucceeded(suceededEvent);

            return result;
        }

        PaymentFailedEvent failedEvent = new PaymentFailedEvent(createFailedMessageId(payment.getId()),
                payment.getOrderId(), payment.getId(), payment.getAmount(), result.reason(), Instant.now());

        paymentTransactionService.markFailed(failedEvent);

        return result;
    }

    private String createSucceededMessageId(UUID paymentId) {
        return "payment:" + paymentId + ":succeeded";
    }

    private String createFailedMessageId(UUID paymentId) {
        return "payment:" + paymentId + ":failed";
    }
}