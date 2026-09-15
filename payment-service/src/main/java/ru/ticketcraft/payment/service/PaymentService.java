package ru.ticketcraft.payment.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentRefundedEvent;
import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import ru.ticketcraft.dto.RefundPaymentCommand;
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

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            throw new IllegalStateException("Payment was already refunded" + ": orderId=" + payment.getOrderId()
                    + ", paymentId=" + payment.getId());
        }

        if (payment.getStatus() == PaymentStatus.FAILED) {
            return PaymentResult.failure("Payment already failed");
        }

        PaymentResult result = paymentGateway.charge(payment.getId(), payment.getOrderId(), payment.getUserId(),
                payment.getAmount());

        if (result.successful()) {

            PaymentSucceededEvent succeededEvent = new PaymentSucceededEvent(createSucceededMessageId(payment.getId()),
                    payment.getOrderId(), payment.getId(), payment.getAmount(), Instant.now());

            paymentTransactionService.markSucceeded(succeededEvent);

            return result;
        }

        PaymentFailedEvent failedEvent = new PaymentFailedEvent(createFailedMessageId(payment.getId()),
                payment.getOrderId(), payment.getId(), payment.getAmount(), result.reason(), Instant.now());

        paymentTransactionService.markFailed(failedEvent);

        return result;
    }

    public PaymentResult process(RefundPaymentCommand command) {

        Payment payment = paymentTransactionService.findPayment(command.paymentId());

        validateRefundCommand(payment, command);

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return PaymentResult.success();
        }

        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new IllegalStateException(
                    "Cannot refund payment" + ": paymentId=" + payment.getId() + ", status=" + payment.getStatus());
        }

        PaymentResult result = paymentGateway.refund(payment.getId(), payment.getOrderId(), payment.getAmount());

        /*
         * Refund failure не превращаем в terminal business event. Kafka retry/DLT
         * должен оставить компенсацию незавершённой для retry/operator intervention.
         */
        if (!result.successful()) {
            throw new IllegalStateException("Payment refund was not completed" + ": paymentId=" + payment.getId()
                    + ", reason=" + result.reason());
        }

        PaymentRefundedEvent refundedEvent = new PaymentRefundedEvent(createRefundedMessageId(payment.getId()),
                payment.getOrderId(), payment.getId(), payment.getAmount(), Instant.now());

        paymentTransactionService.markRefunded(refundedEvent);

        return result;
    }

    private void validateRefundCommand(Payment payment, RefundPaymentCommand command) {

        if (!payment.getOrderId().equals(command.orderId())) {
            throw new IllegalStateException("Refund order mismatch" + ": paymentId=" + payment.getId()
                    + ", expectedOrderId=" + payment.getOrderId() + ", actualOrderId=" + command.orderId());
        }

        if (payment.getAmount().compareTo(command.amount()) != 0) {
            throw new IllegalStateException("Refund amount mismatch" + ": paymentId=" + payment.getId()
                    + ", expectedAmount=" + payment.getAmount() + ", actualAmount=" + command.amount());
        }
    }

    private String createSucceededMessageId(UUID paymentId) {
        return "payment:" + paymentId + ":succeeded";
    }

    private String createFailedMessageId(UUID paymentId) {
        return "payment:" + paymentId + ":failed";
    }

    private String createRefundedMessageId(UUID paymentId) {
        return "payment:" + paymentId + ":refunded";
    }
}