package ru.ticketcraft.payment.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.payment.model.Payment;
import ru.ticketcraft.payment.model.PaymentStatus;
import ru.ticketcraft.payment.repository.PaymentRepository;

@Service
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;

    public PaymentTransactionService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
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
    public void updatePaymentStatus(UUID paymentId, PaymentStatus status) {
        int updatedRows = paymentRepository.updateStatus(paymentId, status, Instant.now());
        if (updatedRows != 1) {
            throw new IllegalStateException("Expected one payment to be updated, but updated rows: " + updatedRows);
        }
    }
}
