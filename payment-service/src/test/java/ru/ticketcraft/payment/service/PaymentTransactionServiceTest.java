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

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import ru.ticketcraft.payment.model.Payment;
import ru.ticketcraft.payment.model.PaymentStatus;
import ru.ticketcraft.payment.outbox.PaymentOutboxService;
import ru.ticketcraft.payment.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class PaymentTransactionServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentOutboxService paymentOutboxService;

    private PaymentTransactionService paymentTransactionService;

    @BeforeEach
    void setUp() {
        paymentTransactionService = new PaymentTransactionService(paymentRepository, paymentOutboxService);
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

        verify(paymentOutboxService, never()).addSucceededEvent(any(PaymentSucceededEvent.class));

        verify(paymentOutboxService, never()).addFailedEvent(any(PaymentFailedEvent.class));
    }

    @Test
    void shouldCreateNewPaymentWhenPaymentDoesNotExist() {
        PaymentRequestedEvent event = createEvent();

        when(paymentRepository.findByOrderId(event.orderId())).thenReturn(Optional.empty());

        when(paymentRepository.insertIfAbsent(any(Payment.class))).thenReturn(true);

        Payment result = paymentTransactionService.getOrCreatePayment(event);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getOrderId()).isEqualTo(event.orderId());
        assertThat(result.getUserId()).isEqualTo(event.userId());
        assertThat(result.getAmount()).isEqualByComparingTo(event.amount());
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.getMessageId()).isEqualTo(event.messageId());
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();

        verify(paymentOutboxService, never()).addSucceededEvent(any(PaymentSucceededEvent.class));

        verify(paymentOutboxService, never()).addFailedEvent(any(PaymentFailedEvent.class));
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

        when(paymentRepository.findByOrderId(event.orderId())).thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingPayment));

        when(paymentRepository.insertIfAbsent(any(Payment.class))).thenReturn(false);

        Payment result = paymentTransactionService.getOrCreatePayment(event);

        assertThat(result).isSameAs(existingPayment);

        verify(paymentRepository, times(2)).findByOrderId(event.orderId());
    }

    @Test
    void shouldThrowWhenInsertWasRejectedAndPaymentCannotBeFound() {
        PaymentRequestedEvent event = createEvent();

        when(paymentRepository.findByOrderId(event.orderId())).thenReturn(Optional.empty())
                .thenReturn(Optional.empty());

        when(paymentRepository.insertIfAbsent(any(Payment.class))).thenReturn(false);

        assertThatThrownBy(() -> paymentTransactionService.getOrCreatePayment(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Payment was not found after failed insert for order 100");

        verify(paymentOutboxService, never()).addSucceededEvent(any(PaymentSucceededEvent.class));

        verify(paymentOutboxService, never()).addFailedEvent(any(PaymentFailedEvent.class));
    }

    @Test
    void shouldMarkPaymentAsSucceededAndCreateOutboxEvent() {
        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        PaymentSucceededEvent event = new PaymentSucceededEvent(createSucceededMessageId(paymentId), 100L, paymentId,
                new BigDecimal("150.00"), Instant.parse("2026-09-09T10:01:00Z"));

        when(paymentRepository.updateStatusFromPending(eq(paymentId), eq(PaymentStatus.SUCCEEDED), any(Instant.class)))
                .thenReturn(1);

        paymentTransactionService.markSucceeded(event);

        verify(paymentRepository).updateStatusFromPending(eq(paymentId), eq(PaymentStatus.SUCCEEDED),
                any(Instant.class));

        verify(paymentOutboxService).addSucceededEvent(event);

        verify(paymentOutboxService, never()).addFailedEvent(any(PaymentFailedEvent.class));
    }

    @Test
    void shouldMarkPaymentAsFailedAndCreateOutboxEvent() {
        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        PaymentFailedEvent event = new PaymentFailedEvent(createFailedMessageId(paymentId), 100L, paymentId, new BigDecimal("150.00"),
                "Insufficient funds", Instant.parse("2026-09-09T10:01:00Z"));

        when(paymentRepository.updateStatusFromPending(eq(paymentId), eq(PaymentStatus.FAILED), any(Instant.class)))
                .thenReturn(1);

        paymentTransactionService.markFailed(event);

        verify(paymentRepository).updateStatusFromPending(eq(paymentId), eq(PaymentStatus.FAILED), any(Instant.class));

        verify(paymentOutboxService).addFailedEvent(event);

        verify(paymentOutboxService, never()).addSucceededEvent(any(PaymentSucceededEvent.class));
    }

    @Test
    void shouldThrowWhenFailedPaymentStatusUpdateUpdatesMoreThanOneRow() {
        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        PaymentFailedEvent event = new PaymentFailedEvent(createFailedMessageId(paymentId), 100L, paymentId, new BigDecimal("150.00"),
                "Insufficient funds", Instant.parse("2026-09-09T10:01:00Z"));

        when(paymentRepository.updateStatusFromPending(eq(paymentId), eq(PaymentStatus.FAILED), any(Instant.class)))
                .thenReturn(2);

        assertThatThrownBy(() -> paymentTransactionService.markFailed(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected number of updated payment rows");

        verify(paymentOutboxService, never()).addFailedEvent(any(PaymentFailedEvent.class));
    }

    @Test
    void shouldThrowWhenSucceededPaymentStatusUpdateUpdatesMoreThanOneRow() {
        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        PaymentSucceededEvent event = new PaymentSucceededEvent(createSucceededMessageId(paymentId), 100L, paymentId,
                new BigDecimal("150.00"), Instant.parse("2026-09-09T10:01:00Z"));

        when(paymentRepository.updateStatusFromPending(eq(paymentId), eq(PaymentStatus.SUCCEEDED), any(Instant.class)))
                .thenReturn(2);

        assertThatThrownBy(() -> paymentTransactionService.markSucceeded(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected number of updated payment rows");

        verify(paymentOutboxService, never()).addSucceededEvent(any(PaymentSucceededEvent.class));

        verify(paymentOutboxService, never()).addFailedEvent(any(PaymentFailedEvent.class));
    }

    @Test
    void shouldNotCreateFailedOutboxEventWhenPaymentAlreadyCompleted() {
        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        PaymentFailedEvent event = new PaymentFailedEvent(createFailedMessageId(paymentId), 100L, paymentId,
                new BigDecimal("150.00"), "Insufficient funds", Instant.parse("2026-09-09T10:01:00Z"));

        when(paymentRepository.updateStatusFromPending(eq(paymentId), eq(PaymentStatus.FAILED), any(Instant.class)))
                .thenReturn(0);

        paymentTransactionService.markFailed(event);

        verify(paymentOutboxService, never()).addFailedEvent(any(PaymentFailedEvent.class));
    }

    @Test
    void shouldNotCreateSucceededOutboxEventWhenPaymentAlreadyCompleted() {
        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        PaymentSucceededEvent event = new PaymentSucceededEvent(createSucceededMessageId(paymentId), 100L, paymentId,
                new BigDecimal("150.00"), Instant.parse("2026-09-09T10:01:00Z"));

        when(paymentRepository.updateStatusFromPending(eq(paymentId), eq(PaymentStatus.SUCCEEDED), any(Instant.class)))
                .thenReturn(0);

        paymentTransactionService.markSucceeded(event);

        verify(paymentOutboxService, never()).addSucceededEvent(any(PaymentSucceededEvent.class));

        verify(paymentOutboxService, never()).addFailedEvent(any(PaymentFailedEvent.class));
    }

    private PaymentRequestedEvent createEvent() {
        return new PaymentRequestedEvent("message-1", 100L, 200L, new BigDecimal("150.00"),
                Instant.parse("2026-09-09T10:00:00Z"));
    }

    private Payment createPayment(UUID paymentId, PaymentStatus status) {
        Instant now = Instant.parse("2026-09-09T10:00:00Z");
        return new Payment(paymentId, 100L, 200L, new BigDecimal("150.00"), status, "message-1", now, now);
    }

    private String createFailedMessageId(UUID paymentId) {
        return "payment:" + paymentId + ":failed";
    }

    private String createSucceededMessageId(UUID paymentId) {
        return "payment:" + paymentId + ":succeeded";
    }
}