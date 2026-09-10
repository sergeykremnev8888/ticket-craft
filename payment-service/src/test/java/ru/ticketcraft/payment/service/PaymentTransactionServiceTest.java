package ru.ticketcraft.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.payment.model.Payment;
import ru.ticketcraft.payment.model.PaymentStatus;
import ru.ticketcraft.payment.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class PaymentTransactionServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    private PaymentTransactionService paymentTransactionService;

    @BeforeEach
    void setUp() {
        paymentTransactionService = new PaymentTransactionService(paymentRepository);
    }

    @Test
    void shouldReturnExistingPayment() {
        PaymentRequestedEvent event = createEvent();

        Payment existingPayment = createPayment(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                PaymentStatus.PENDING);

        when(paymentRepository.findByOrderId(event.orderId())).thenReturn(Optional.of(existingPayment));

        Payment result = paymentTransactionService.getOrCreatePayment(event);

        assertThat(result).isSameAs(existingPayment);

        verify(paymentRepository).findByOrderId(event.orderId());

        verify(paymentRepository, never()).insertIfAbsent(any(Payment.class));
    }

    @Test
    void shouldCreateNewPaymentWhenPaymentDoesNotExist() {
        PaymentRequestedEvent event = createEvent();

        when(paymentRepository.findByOrderId(event.orderId())).thenReturn(Optional.empty());

        when(paymentRepository.insertIfAbsent(any(Payment.class))).thenReturn(true);

        Payment result = paymentTransactionService.getOrCreatePayment(event);

        assertThat(result).isNotNull();
        assertThat(result.getOrderId()).isEqualTo(event.orderId());
        assertThat(result.getUserId()).isEqualTo(event.userId());
        assertThat(result.getAmount()).isEqualByComparingTo(event.amount());
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.getMessageId()).isEqualTo(event.messageId());
    }

    @Test
    void shouldInsertPaymentWithExpectedValues() {
        PaymentRequestedEvent event = createEvent();

        when(paymentRepository.findByOrderId(event.orderId())).thenReturn(Optional.empty());

        when(paymentRepository.insertIfAbsent(any(Payment.class))).thenReturn(true);

        paymentTransactionService.getOrCreatePayment(event);

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);

        verify(paymentRepository).insertIfAbsent(captor.capture());

        Payment savedPayment = captor.getValue();

        assertThat(savedPayment.getId()).isNotNull();
        assertThat(savedPayment.getOrderId()).isEqualTo(event.orderId());
        assertThat(savedPayment.getUserId()).isEqualTo(event.userId());
        assertThat(savedPayment.getAmount()).isEqualByComparingTo(event.amount());
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(savedPayment.getMessageId()).isEqualTo(event.messageId());
        assertThat(savedPayment.getCreatedAt()).isNotNull();
        assertThat(savedPayment.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldLoadExistingPaymentWhenInsertWasRejected() {
        PaymentRequestedEvent event = createEvent();

        Payment existingPayment = createPayment(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                PaymentStatus.PENDING);

        when(paymentRepository.findByOrderId(event.orderId()))
                .thenReturn(Optional.<Payment>empty())
                .thenReturn(Optional.<Payment>of(existingPayment));

        when(paymentRepository.insertIfAbsent(any(Payment.class))).thenReturn(false);

        Payment result = paymentTransactionService.getOrCreatePayment(event);

        assertThat(result).isSameAs(existingPayment);

        verify(paymentRepository, times(2)).findByOrderId(event.orderId());
    }

    @Test
    void shouldThrowWhenInsertWasRejectedAndPaymentCannotBeFound() {
        PaymentRequestedEvent event = createEvent();

        when(paymentRepository.findByOrderId(event.orderId()))
                .thenReturn(Optional.<Payment>empty())
                .thenReturn(Optional.<Payment>empty());

        when(paymentRepository.insertIfAbsent(any(Payment.class))).thenReturn(false);

        assertThatThrownBy(() -> paymentTransactionService.getOrCreatePayment(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Payment was not found after failed insert for order 100");
    }

    @Test
    void shouldUpdatePaymentStatusSuccessfully() {
        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(paymentRepository.updateStatus(eq(paymentId), eq(PaymentStatus.SUCCEEDED), any(Instant.class)))
                .thenReturn(1);

        paymentTransactionService.updatePaymentStatus(paymentId, PaymentStatus.SUCCEEDED);

        verify(paymentRepository).updateStatus(eq(paymentId), eq(PaymentStatus.SUCCEEDED), any(Instant.class));
    }

    @Test
    void shouldThrowWhenPaymentStatusUpdateDoesNotUpdateExactlyOneRow() {
        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(paymentRepository.updateStatus(eq(paymentId), eq(PaymentStatus.FAILED), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> paymentTransactionService.updatePaymentStatus(paymentId, PaymentStatus.FAILED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected one payment to be updated, but updated rows: 0");
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