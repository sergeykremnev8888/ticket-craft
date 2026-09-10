package ru.ticketcraft.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.payment.gateway.PaymentGateway;
import ru.ticketcraft.payment.gateway.PaymentResult;
import ru.ticketcraft.payment.model.Payment;
import ru.ticketcraft.payment.model.PaymentStatus;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentTransactionService paymentTransactionService;

    @Mock
    private PaymentGateway paymentGateway;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentTransactionService, paymentGateway);
    }

    @Test
    void shouldProcessSuccessfulPayment() {
        PaymentRequestedEvent event = createEvent();

        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        Payment payment = createPayment(paymentId, PaymentStatus.PENDING);

        when(paymentTransactionService.getOrCreatePayment(event)).thenReturn(payment);

        when(paymentGateway.charge(paymentId, event.orderId(), event.userId(), event.amount()))
                .thenReturn(PaymentResult.success());

        PaymentResult result = paymentService.process(event);

        assertThat(result.successful()).isTrue();

        verify(paymentGateway).charge(paymentId, event.orderId(), event.userId(), event.amount());

        verify(paymentTransactionService).updatePaymentStatus(paymentId, PaymentStatus.SUCCEEDED);
    }

    @Test
    void shouldMarkPaymentAsFailedWhenGatewayFails() {
        PaymentRequestedEvent event = createEvent();

        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        Payment payment = createPayment(paymentId, PaymentStatus.PENDING);

        PaymentResult gatewayResult = PaymentResult.failure("Insufficient funds");

        when(paymentTransactionService.getOrCreatePayment(event)).thenReturn(payment);

        when(paymentGateway.charge(paymentId, event.orderId(), event.userId(), event.amount()))
                .thenReturn(gatewayResult);

        PaymentResult result = paymentService.process(event);

        assertThat(result.successful()).isFalse();

        verify(paymentGateway).charge(paymentId, event.orderId(), event.userId(), event.amount());

        verify(paymentTransactionService).updatePaymentStatus(paymentId, PaymentStatus.FAILED);
    }

    @Test
    void shouldReturnSuccessWithoutChargingWhenPaymentAlreadySucceeded() {
        PaymentRequestedEvent event = createEvent();

        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        Payment payment = createPayment(paymentId, PaymentStatus.SUCCEEDED);

        when(paymentTransactionService.getOrCreatePayment(event)).thenReturn(payment);

        PaymentResult result = paymentService.process(event);

        assertThat(result.successful()).isTrue();

        verify(paymentGateway, never()).charge(any(UUID.class), any(Long.class), any(Long.class),
                any(BigDecimal.class));

        verify(paymentTransactionService, never()).updatePaymentStatus(any(UUID.class), any(PaymentStatus.class));
    }

    @Test
    void shouldReturnFailureWithoutChargingWhenPaymentAlreadyFailed() {
        PaymentRequestedEvent event = createEvent();

        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        Payment payment = createPayment(paymentId, PaymentStatus.FAILED);

        when(paymentTransactionService.getOrCreatePayment(event)).thenReturn(payment);

        PaymentResult result = paymentService.process(event);

        assertThat(result.successful()).isFalse();
        assertThat(result.reason()).isEqualTo("Payment already failed");

        verify(paymentGateway, never()).charge(any(UUID.class), any(Long.class), any(Long.class),
                any(BigDecimal.class));

        verify(paymentTransactionService, never()).updatePaymentStatus(any(UUID.class), any(PaymentStatus.class));
    }

    @Test
    void shouldRetryPendingPayment() {
        PaymentRequestedEvent event = createEvent();

        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        Payment payment = createPayment(paymentId, PaymentStatus.PENDING);

        when(paymentTransactionService.getOrCreatePayment(event)).thenReturn(payment);

        when(paymentGateway.charge(paymentId, event.orderId(), event.userId(), event.amount()))
                .thenReturn(PaymentResult.success());

        PaymentResult result = paymentService.process(event);

        assertThat(result.successful()).isTrue();

        verify(paymentGateway).charge(paymentId, event.orderId(), event.userId(), event.amount());

        verify(paymentTransactionService).updatePaymentStatus(paymentId, PaymentStatus.SUCCEEDED);
    }

    private PaymentRequestedEvent createEvent() {
        return new PaymentRequestedEvent("message-1", 100L, 200L, new BigDecimal("150.00"),
                Instant.parse("2026-09-09T10:00:00Z"));
    }

    private Payment createPayment(UUID paymentId, PaymentStatus status) {
        Instant now = Instant.parse("2026-09-09T10:00:00Z");

        return new Payment(paymentId, 100L, 200L, new BigDecimal("150.00"), status, "message-1", now, now);
    }
}