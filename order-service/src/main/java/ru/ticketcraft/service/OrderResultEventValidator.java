package ru.ticketcraft.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentRefundedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import ru.ticketcraft.dto.TicketConfirmationFailedEvent;
import ru.ticketcraft.dto.TicketConfirmedEvent;
import ru.ticketcraft.dto.TicketReleasedEvent;
import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketReservedEvent;

@Component
public class OrderResultEventValidator {

    public void validate(PaymentSucceededEvent event) {
        requirePresent(event, "PaymentSucceededEvent");

        validatePayment(event.messageId(), event.orderId(), event.paymentId(), event.amount(), event.createdAt());
    }

    public void validate(PaymentFailedEvent event) {
        requirePresent(event, "PaymentFailedEvent");

        validatePayment(event.messageId(), event.orderId(), event.paymentId(), event.amount(), event.createdAt());
    }

    public void validate(PaymentRefundedEvent event) {
        requirePresent(event, "PaymentRefundedEvent");

        validatePayment(event.messageId(), event.orderId(), event.paymentId(), event.amount(), event.createdAt());
    }

    public void validate(TicketReservedEvent event) {
        requirePresent(event, "TicketReservedEvent");

        validateReservation(event.messageId(),
                            event.orderId(),
                            event.reservationId(),
                            event.ticketId(),
                            event.occurredAt());

        requirePresent(event.eventId(), "eventId");
        validateAmount(event.price(), "price");
    }

    public void validate(TicketReservationFailedEvent event) {
        requirePresent(event, "TicketReservationFailedEvent");

        validateReservation(event.messageId(),
                            event.orderId(),
                            event.reservationId(),
                            event.ticketId(),
                            event.occurredAt());
    }

    public void validate(TicketReleasedEvent event) {
        requirePresent(event, "TicketReleasedEvent");

        validateReservation(event.messageId(),
                            event.orderId(),
                            event.reservationId(),
                            event.ticketId(),
                            event.occurredAt());
    }

    public void validate(TicketConfirmedEvent event) {
        requirePresent(event, "TicketConfirmedEvent");

        validateReservation(event.messageId(),
                            event.orderId(),
                            event.reservationId(),
                            event.ticketId(),
                            event.occurredAt());
    }

    public void validate(TicketConfirmationFailedEvent event) {
        requirePresent(event, "TicketConfirmationFailedEvent");

        validateReservation(event.messageId(),
                            event.orderId(),
                            event.reservationId(),
                            event.ticketId(),
                            event.occurredAt());

        requirePresent(event.reason(), "reason");
    }

    private void validatePayment(String messageId, Long orderId, UUID paymentId, BigDecimal amount, Instant createdAt) {
        validateCommon(messageId, orderId);

        requirePresent(paymentId, "paymentId");
        requirePresent(createdAt, "createdAt");

        validateAmount(amount, "amount");
    }

    private void validateReservation(String messageId,
                                     Long orderId,
                                     UUID reservationId,
                                     UUID ticketId,
                                     Instant occurredAt) {
        validateCommon(messageId, orderId);

        requirePresent(reservationId, "reservationId");
        requirePresent(ticketId, "ticketId");
        requirePresent(occurredAt, "occurredAt");
    }

    private void validateCommon(String messageId, Long orderId) {
        if (messageId == null || messageId.isBlank() || messageId.length() > 255) {
            throw new IllegalArgumentException("messageId must contain between 1 and 255 characters");
        }

        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
    }

    private void validateAmount(BigDecimal amount, String fieldName) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }

        BigDecimal normalized;

        try {
            normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    fieldName + " must be exactly representable " + "with two decimal places", exception);
        }

        if (normalized.precision() > 19) {
            throw new IllegalArgumentException(fieldName + " exceeds NUMERIC(19, 2)");
        }
    }

    private void requirePresent(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
    }
}