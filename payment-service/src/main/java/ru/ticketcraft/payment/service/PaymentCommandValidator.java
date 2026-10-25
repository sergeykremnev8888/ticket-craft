package ru.ticketcraft.payment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import ru.ticketcraft.common.dto.PaymentRequestedEvent;
import ru.ticketcraft.common.dto.RefundPaymentCommand;

@Component
public class PaymentCommandValidator {

    public void validate(PaymentRequestedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Payment request must not be null");
        }

        validateMessageId(event.messageId());
        validatePositiveId(event.orderId(), "orderId");
        validatePositiveId(event.userId(), "userId");
        validateAmount(event.amount());

        if (event.createdAt() == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }

    public void validate(RefundPaymentCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Refund command must not be null");
        }

        validateMessageId(command.messageId());
        validatePositiveId(command.orderId(), "orderId");
        validateAmount(command.amount());

        if (command.paymentId() == null) {
            throw new IllegalArgumentException("paymentId must not be null");
        }

        if (command.reason() == null) {
            throw new IllegalArgumentException("reason must not be null");
        }

        if (command.occurredAt() == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }

    private void validateMessageId(String messageId) {
        if (messageId == null || messageId.isBlank() || messageId.length() > 128) {
            throw new IllegalArgumentException("messageId must contain between 1 and 128 characters");
        }
    }

    private void validatePositiveId(Long id, String fieldName) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }

        BigDecimal normalized;

        try {
            normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("amount must be exactly representable " + "with two decimal places",
                    exception);
        }

        if (normalized.precision() > 19) {
            throw new IllegalArgumentException("amount exceeds NUMERIC(19, 2)");
        }
    }
}