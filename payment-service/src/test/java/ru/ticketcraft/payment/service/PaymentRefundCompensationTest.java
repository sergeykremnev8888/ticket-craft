package ru.ticketcraft.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

import ru.ticketcraft.dto.PaymentRefundedEvent;
import ru.ticketcraft.dto.RefundPaymentCommand;
import ru.ticketcraft.dto.TicketConfirmationFailureReason;
import ru.ticketcraft.payment.gateway.PaymentGateway;
import ru.ticketcraft.payment.gateway.PaymentResult;
import ru.ticketcraft.payment.model.Payment;
import ru.ticketcraft.payment.model.PaymentStatus;
import ru.ticketcraft.payment.outbox.PaymentOutboxService;
import ru.ticketcraft.payment.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class PaymentRefundCompensationTest {

    private static final UUID PAYMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final Long ORDER_ID = 100L;

    private static final Long USER_ID = 200L;

    private static final BigDecimal AMOUNT = new BigDecimal("150.00");

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentOutboxService paymentOutboxService;

    @Mock
    private PaymentGateway paymentGateway;

    private PaymentTransactionService paymentTransactionService;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {

        paymentTransactionService = new PaymentTransactionService(paymentRepository, paymentOutboxService);

        paymentService = new PaymentService(paymentTransactionService, paymentGateway);
    }

    @Test
    void shouldRefundSucceededPaymentAndCreateRefundedEvent() {

        Payment payment = createPayment(PaymentStatus.SUCCEEDED);

        RefundPaymentCommand command = createRefundCommand();

        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        when(paymentGateway.refund(PAYMENT_ID, ORDER_ID, AMOUNT)).thenReturn(PaymentResult.success());

        when(paymentRepository.updateStatus(eq(PAYMENT_ID), eq(PaymentStatus.SUCCEEDED), eq(PaymentStatus.REFUNDED),
                any(Instant.class))).thenReturn(1);

        PaymentResult result = paymentService.process(command);

        assertThat(result.successful()).isTrue();

        verify(paymentGateway).refund(PAYMENT_ID, ORDER_ID, AMOUNT);

        verify(paymentRepository).updateStatus(eq(PAYMENT_ID), eq(PaymentStatus.SUCCEEDED), eq(PaymentStatus.REFUNDED),
                any(Instant.class));

        ArgumentCaptor<PaymentRefundedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentRefundedEvent.class);

        verify(paymentOutboxService).addRefundedEvent(eventCaptor.capture());

        PaymentRefundedEvent event = eventCaptor.getValue();

        assertThat(event.messageId()).isEqualTo("payment:" + PAYMENT_ID + ":refunded");

        assertThat(event.orderId()).isEqualTo(ORDER_ID);

        assertThat(event.paymentId()).isEqualTo(PAYMENT_ID);

        assertThat(event.amount()).isEqualByComparingTo(AMOUNT);

        assertThat(event.createdAt()).isNotNull();
    }

    @Test
    void shouldReturnSuccessWithoutSecondRefundWhenAlreadyRefunded() {

        Payment payment = createPayment(PaymentStatus.REFUNDED);

        RefundPaymentCommand command = createRefundCommand();

        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        PaymentResult result = paymentService.process(command);

        assertThat(result.successful()).isTrue();

        /*
         * Критический idempotency invariant:
         *
         * redelivery RefundPaymentCommand не вызывает внешний refund второй раз.
         */
        verify(paymentGateway, never()).refund(any(UUID.class), any(Long.class), any(BigDecimal.class));

        verify(paymentRepository, never()).updateStatus(any(UUID.class), any(PaymentStatus.class),
                any(PaymentStatus.class), any(Instant.class));

        verify(paymentOutboxService, never()).addRefundedEvent(any(PaymentRefundedEvent.class));
    }

    @Test
    void shouldKeepPaymentSucceededWhenGatewayRefundFails() {

        Payment payment = createPayment(PaymentStatus.SUCCEEDED);

        RefundPaymentCommand command = createRefundCommand();

        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        when(paymentGateway.refund(PAYMENT_ID, ORDER_ID, AMOUNT))
                .thenReturn(PaymentResult.failure("PROVIDER_UNAVAILABLE"));

        assertThatThrownBy(() -> paymentService.process(command)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Payment refund was not completed").hasMessageContaining("PROVIDER_UNAVAILABLE");

        /*
         * Provider refund не подтверждён:
         *
         * payment нельзя помечать REFUNDED. Kafka retry/DLT должен повторить command.
         */
        verify(paymentRepository, never()).updateStatus(any(UUID.class), any(PaymentStatus.class),
                any(PaymentStatus.class), any(Instant.class));

        verify(paymentOutboxService, never()).addRefundedEvent(any(PaymentRefundedEvent.class));
    }

    @Test
    void shouldRejectRefundWhenPaymentIsPending() {

        Payment payment = createPayment(PaymentStatus.PENDING);

        RefundPaymentCommand command = createRefundCommand();

        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.process(command)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot refund payment").hasMessageContaining("PENDING");

        verify(paymentGateway, never()).refund(any(UUID.class), any(Long.class), any(BigDecimal.class));

        verify(paymentOutboxService, never()).addRefundedEvent(any(PaymentRefundedEvent.class));
    }

    @Test
    void shouldRejectRefundWhenOrderIdDoesNotMatchPayment() {

        Payment payment = createPayment(PaymentStatus.SUCCEEDED);

        RefundPaymentCommand command = new RefundPaymentCommand("saga:test:refund-payment", 999L, PAYMENT_ID, AMOUNT,
                TicketConfirmationFailureReason.RESERVATION_EXPIRED, Instant.now());

        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.process(command)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refund order mismatch");

        verify(paymentGateway, never()).refund(any(UUID.class), any(Long.class), any(BigDecimal.class));

        verify(paymentOutboxService, never()).addRefundedEvent(any(PaymentRefundedEvent.class));
    }

    @Test
    void shouldRejectRefundWhenAmountDoesNotMatchPayment() {

        Payment payment = createPayment(PaymentStatus.SUCCEEDED);

        RefundPaymentCommand command = new RefundPaymentCommand("saga:test:refund-payment", ORDER_ID, PAYMENT_ID,
                new BigDecimal("999.00"), TicketConfirmationFailureReason.RESERVATION_EXPIRED, Instant.now());

        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.process(command)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refund amount mismatch");

        verify(paymentGateway, never()).refund(any(UUID.class), any(Long.class), any(BigDecimal.class));

        verify(paymentOutboxService, never()).addRefundedEvent(any(PaymentRefundedEvent.class));
    }

    @Test
    void shouldNotCreateSecondRefundedEventWhenStatusAlreadyRefunded() {

        PaymentRefundedEvent event = new PaymentRefundedEvent("payment:" + PAYMENT_ID + ":refunded", ORDER_ID,
                PAYMENT_ID, AMOUNT, Instant.now());

        when(paymentRepository.updateStatus(eq(PAYMENT_ID), eq(PaymentStatus.SUCCEEDED), eq(PaymentStatus.REFUNDED),
                any(Instant.class))).thenReturn(0);

        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(createPayment(PaymentStatus.REFUNDED)));

        paymentTransactionService.markRefunded(event);

        verify(paymentRepository).findById(PAYMENT_ID);

        /*
         * CAS=0 + current state REFUNDED означает idempotent replay.
         */
        verify(paymentOutboxService, never()).addRefundedEvent(any(PaymentRefundedEvent.class));
    }

    @Test
    void shouldRejectRefundedTransitionFromFailedPayment() {

        PaymentRefundedEvent event = new PaymentRefundedEvent("payment:" + PAYMENT_ID + ":refunded", ORDER_ID,
                PAYMENT_ID, AMOUNT, Instant.now());

        when(paymentRepository.updateStatus(eq(PAYMENT_ID), eq(PaymentStatus.SUCCEEDED), eq(PaymentStatus.REFUNDED),
                any(Instant.class))).thenReturn(0);

        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(createPayment(PaymentStatus.FAILED)));

        assertThatThrownBy(() -> paymentTransactionService.markRefunded(event))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Cannot transition payment")
                .hasMessageContaining("FAILED").hasMessageContaining("REFUNDED");

        verify(paymentOutboxService, never()).addRefundedEvent(any(PaymentRefundedEvent.class));
    }

    private RefundPaymentCommand createRefundCommand() {

        return new RefundPaymentCommand(
                "saga:" + UUID.fromString("33333333-3333-3333-3333-333333333333") + ":refund-payment", ORDER_ID,
                PAYMENT_ID, AMOUNT, TicketConfirmationFailureReason.RESERVATION_EXPIRED,
                Instant.parse("2026-09-15T10:00:00Z"));
    }

    private Payment createPayment(PaymentStatus status) {

        Instant now = Instant.parse("2026-09-15T09:00:00Z");

        return new Payment(PAYMENT_ID, ORDER_ID, USER_ID, AMOUNT, status, "saga:test:payment-requested", now, now);
    }
}