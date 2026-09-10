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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
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

        ArgumentCaptor<PaymentSucceededEvent> eventCaptor = ArgumentCaptor.forClass(PaymentSucceededEvent.class);

        verify(paymentTransactionService).markSucceeded(eventCaptor.capture());

        PaymentSucceededEvent succeededEvent = eventCaptor.getValue();

        assertThat(succeededEvent.messageId()).isEqualTo("payment:" + paymentId + ":succeeded");
        assertThat(succeededEvent.orderId()).isEqualTo(event.orderId());
        assertThat(succeededEvent.paymentId()).isEqualTo(paymentId);
        assertThat(succeededEvent.amount()).isEqualByComparingTo(event.amount());
        assertThat(succeededEvent.createdAt()).isNotNull();

        verify(paymentTransactionService, never()).markFailed(any(PaymentFailedEvent.class));
    }

    @Test
    void shouldMarkPaymentAsFailedWhenGatewayReturnsFailure() {
        PaymentRequestedEvent event = createEvent();

        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        Payment payment = createPayment(paymentId, PaymentStatus.PENDING);

        PaymentResult gatewayResult = PaymentResult.failure("Insufficient funds");

        when(paymentTransactionService.getOrCreatePayment(event)).thenReturn(payment);

        when(paymentGateway.charge(paymentId, event.orderId(), event.userId(), event.amount()))
                .thenReturn(gatewayResult);

        PaymentResult result = paymentService.process(event);

        assertThat(result.successful()).isFalse();
        assertThat(result.reason()).isEqualTo("Insufficient funds");

        verify(paymentGateway).charge(paymentId, event.orderId(), event.userId(), event.amount());

        ArgumentCaptor<PaymentFailedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentFailedEvent.class);

        verify(paymentTransactionService).markFailed(eventCaptor.capture());

        PaymentFailedEvent failedEvent = eventCaptor.getValue();

        assertThat(failedEvent.messageId()).isEqualTo("payment:" + paymentId + ":failed");
        assertThat(failedEvent.orderId()).isEqualTo(event.orderId());
        assertThat(failedEvent.paymentId()).isEqualTo(paymentId);
        assertThat(failedEvent.amount()).isEqualByComparingTo(event.amount());
        assertThat(failedEvent.reason()).isEqualTo("Insufficient funds");
        assertThat(failedEvent.createdAt()).isNotNull();

        verify(paymentTransactionService, never()).markSucceeded(any(PaymentSucceededEvent.class));
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

        verify(paymentTransactionService, never()).markSucceeded(any(PaymentSucceededEvent.class));

        verify(paymentTransactionService, never()).markFailed(any(PaymentFailedEvent.class));
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

        verify(paymentTransactionService, never()).markSucceeded(any(PaymentSucceededEvent.class));

        verify(paymentTransactionService, never()).markFailed(any(PaymentFailedEvent.class));
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

        verify(paymentTransactionService).markSucceeded(any(PaymentSucceededEvent.class));
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