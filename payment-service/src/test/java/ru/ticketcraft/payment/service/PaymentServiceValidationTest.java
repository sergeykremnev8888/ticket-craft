package ru.ticketcraft.payment.service;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.dto.RefundPaymentCommand;
import ru.ticketcraft.dto.TicketConfirmationFailureReason;
import ru.ticketcraft.payment.gateway.PaymentGateway;

@ExtendWith(MockitoExtension.class)
class PaymentServiceValidationTest {

    @Mock
    private PaymentTransactionService paymentTransactionService;

    @Mock
    private PaymentGateway paymentGateway;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentTransactionService, paymentGateway, new PaymentCommandValidator());
    }

    @Test
    void shouldRejectInvalidPaymentBeforeCallingDependencies() {
        PaymentRequestedEvent event = new PaymentRequestedEvent("payment-invalid", 123L, 10L, new BigDecimal("-100.00"),
                Instant.parse("2026-01-01T10:00:00Z"));

        assertThatIllegalArgumentException().isThrownBy(() -> paymentService.process(event));

        verifyNoInteractions(paymentTransactionService, paymentGateway);
    }

    @Test
    void shouldRejectInvalidRefundBeforeCallingDependencies() {
        RefundPaymentCommand command = new RefundPaymentCommand("refund-invalid", 123L, UUID.randomUUID(),
                new BigDecimal("100.001"), TicketConfirmationFailureReason.RESERVATION_EXPIRED,
                Instant.parse("2026-01-01T10:00:00Z"));

        assertThatIllegalArgumentException().isThrownBy(() -> paymentService.process(command));

        verifyNoInteractions(paymentTransactionService, paymentGateway);
    }
}