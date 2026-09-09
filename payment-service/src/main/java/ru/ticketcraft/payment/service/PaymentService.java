package ru.ticketcraft.payment.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.payment.gateway.PaymentGateway;
import ru.ticketcraft.payment.gateway.PaymentResult;
import ru.ticketcraft.payment.model.Payment;
import ru.ticketcraft.payment.model.PaymentStatus;
import ru.ticketcraft.payment.repository.PaymentRepository;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;

    public PaymentService(PaymentRepository paymentRepository, PaymentGateway paymentGateway) {

        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
    }

    @Transactional
    public PaymentResult process(PaymentRequestedEvent event) {
        Payment existingPayment = paymentRepository.findByOrderId(event.orderId()).orElse(null);
        if (existingPayment != null) {
            return existingPayment.getStatus() == PaymentStatus.SUCCEEDED ? PaymentResult.success()
                    : PaymentResult.failure("Payment already exists: " + existingPayment.getStatus());
        }

        Instant now = Instant.now();

        Payment payment = new Payment(UUID.randomUUID(), event.orderId(), event.userId(), event.amount(),
                PaymentStatus.PENDING, event.messageId(), now, now);

        boolean inserted = paymentRepository.insertIfAbsent(payment);
        if (!inserted) {
            return PaymentResult.failure("Payment already exists");
        }

        PaymentResult result = paymentGateway.charge(event.orderId(), event.userId(), event.amount());
        PaymentStatus status = result.successful() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;

        paymentRepository.updateStatus(payment.getId(), status, Instant.now());

        return result;
    }
}
