package ru.ticketcraft.payment.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import ru.ticketcraft.payment.model.Payment;
import ru.ticketcraft.payment.model.PaymentStatus;
import ru.ticketcraft.payment.outbox.PaymentOutboxService;
import ru.ticketcraft.payment.repository.PaymentRepository;

@Service
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final PaymentOutboxService paymentOutboxService;

    public PaymentTransactionService(PaymentRepository paymentRepository, PaymentOutboxService paymentOutboxService) {
        this.paymentRepository = paymentRepository;
        this.paymentOutboxService = paymentOutboxService;
    }

    @Transactional
    public Payment getOrCreatePayment(PaymentRequestedEvent event) {
        Payment existingPayment = paymentRepository.findByOrderId(event.orderId()).orElse(null);

        if (existingPayment != null) {
            return existingPayment;
        }

        Instant now = Instant.now();

        Payment payment = new Payment(UUID.randomUUID(), event.orderId(), event.userId(), event.amount(),
                PaymentStatus.PENDING, event.messageId(), now, now);

        boolean inserted = paymentRepository.insertIfAbsent(payment);
        if (inserted) {
            return payment;
        }

        return paymentRepository.findByOrderId(event.orderId()).orElseThrow(() -> new IllegalStateException(
                "Payment was not found after failed insert for order " + event.orderId()));
    }

    @Transactional
    public void markSucceeded(PaymentSucceededEvent event) {
        transitionToTerminal(event.paymentId(), PaymentStatus.SUCCEEDED,
                () -> paymentOutboxService.addSucceededEvent(event));
    }

    @Transactional
    public void markFailed(PaymentFailedEvent event) {
        transitionToTerminal(event.paymentId(), PaymentStatus.FAILED, () -> paymentOutboxService.addFailedEvent(event));
    }

    private void transitionToTerminal(UUID paymentId, PaymentStatus targetStatus, Runnable outboxAction) {
        int updatedRows = paymentRepository.updateStatusFromPending(paymentId, targetStatus, Instant.now());
        if (updatedRows == 1) {
            outboxAction.run();
            return;
        }

        if (updatedRows > 1) {
            throw new IllegalStateException("Unexpected number of updated payment rows: " + updatedRows);
        }

        Payment currentPayment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));

        if (currentPayment.getStatus() == targetStatus) {
            return;
        }

        throw new IllegalStateException("Cannot transition payment " + paymentId + " from " + currentPayment.getStatus()
                + " to " + targetStatus);
    }
}