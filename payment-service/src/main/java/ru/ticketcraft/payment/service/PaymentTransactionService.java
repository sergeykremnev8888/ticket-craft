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
    public boolean markSucceeded(PaymentSucceededEvent event) {
        int updatedRows = paymentRepository.updateStatusFromPending(event.paymentId(), PaymentStatus.SUCCEEDED,
                Instant.now());

        if (updatedRows == 0) {
            return false;
        }

        if (updatedRows != 1) {
            throw new IllegalStateException("Unexpected number of updated payment rows: " + updatedRows);
        }

        paymentOutboxService.addSucceededEvent(event);

        return true;
    }

    @Transactional
    public boolean markFailed(PaymentFailedEvent event) {
        int updatedRows = paymentRepository.updateStatusFromPending(event.paymentId(), PaymentStatus.FAILED,
                Instant.now());

        if (updatedRows == 0) {
            return false;
        }

        if (updatedRows != 1) {
            throw new IllegalStateException("Unexpected number of updated payment rows: " + updatedRows);
        }

        paymentOutboxService.addFailedEvent(event);

        return true;
    }
}