package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentRefundedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import ru.ticketcraft.dto.TicketConfirmationFailedEvent;
import ru.ticketcraft.dto.TicketConfirmationFailureReason;
import ru.ticketcraft.dto.TicketConfirmedEvent;
import ru.ticketcraft.dto.TicketReleasedEvent;
import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketReservedEvent;

class OrderResultEventValidatorTest {

    private static final UUID PAYMENT_ID = UUID.randomUUID();
    private static final UUID RESERVATION_ID = UUID.randomUUID();
    private static final UUID TICKET_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();

    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T10:00:00Z");

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private final OrderResultEventValidator validator = new OrderResultEventValidator();

    @Test
    void shouldAcceptValidEventsOfAllSupportedTypes() {
        assertDoesNotThrow(() -> validator.validate(paymentSucceeded("payment-success", PAYMENT_ID, AMOUNT)));

        assertDoesNotThrow(() -> validator.validate(new PaymentFailedEvent("payment-failed", 123L, PAYMENT_ID, AMOUNT,
                "DECLINED", OCCURRED_AT)));

        assertDoesNotThrow(() -> validator.validate(new PaymentRefundedEvent("payment-refunded", 123L, PAYMENT_ID,
                AMOUNT, OCCURRED_AT)));

        assertDoesNotThrow(() -> validator.validate(ticketReserved(EVENT_ID, AMOUNT)));

        assertDoesNotThrow(() -> validator.validate(new TicketReservationFailedEvent("reservation-failed", 123L,
                RESERVATION_ID, TICKET_ID, "TICKET_UNAVAILABLE", OCCURRED_AT)));

        assertDoesNotThrow(() -> validator.validate(new TicketReleasedEvent("ticket-released", 123L, RESERVATION_ID,
                TICKET_ID, OCCURRED_AT)));

        assertDoesNotThrow(() -> validator.validate(new TicketConfirmedEvent("ticket-confirmed", 123L, RESERVATION_ID,
                TICKET_ID, OCCURRED_AT)));

        assertDoesNotThrow(() -> validator.validate(new TicketConfirmationFailedEvent("confirmation-failed", 123L,
                RESERVATION_ID, TICKET_ID, TicketConfirmationFailureReason.RESERVATION_EXPIRED, OCCURRED_AT)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t", "\n" })
    void shouldRejectMissingOrBlankMessageId(String messageId) {
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(paymentSucceeded(messageId,
                                                                                                  PAYMENT_ID,
                                                                                                  AMOUNT)))
                                            .withMessage("messageId must contain between 1 and 255 characters");
    }

    @Test
    void shouldRejectTooLongMessageId() {
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(paymentSucceeded("a".repeat(256),
                                                                                                  PAYMENT_ID,
                                                                                                  AMOUNT)))
                                            .withMessage("messageId must contain between 1 and 255 characters");
    }

    @Test
    void shouldAcceptMessageIdWithMaximumLength() {
        assertDoesNotThrow(() -> validator.validate(paymentSucceeded("a".repeat(255), PAYMENT_ID, AMOUNT)));
    }

    @Test
    void shouldRejectMissingPaymentId() {
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(paymentSucceeded("message-1",
                                                                                                  null,
                                                                                                  AMOUNT)))
                                            .withMessage("paymentId must not be null");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = { 0, -1 })
    void shouldRejectInvalidOrderId(Long orderId) {
        PaymentSucceededEvent event = new PaymentSucceededEvent("message-1", orderId, PAYMENT_ID, AMOUNT, OCCURRED_AT);

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(event))
                                            .withMessage("orderId must be positive");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "0.00", "-0.01", "100.001", "100000000000000000.00" })
    void shouldRejectInvalidPaymentAmount(String value) {
        BigDecimal amount = value == null ? null : new BigDecimal(value);

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(paymentSucceeded("message-1",
                                                                                                  PAYMENT_ID,
                                                                                                  amount)))
                                            .withMessageContaining("amount");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "0.00", "-0.01", "100.001", "100000000000000000.00" })
    void shouldRejectInvalidReservationPrice(String value) {
        BigDecimal price = value == null ? null : new BigDecimal(value);

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(ticketReserved(EVENT_ID, price)))
                                            .withMessageContaining("price");
    }

    @ParameterizedTest
    @ValueSource(strings = { "0.01", "100", "100.000", "99999999999999999.99" })
    void shouldAcceptValidMonetaryValues(String value) {
        BigDecimal amount = new BigDecimal(value);

        assertDoesNotThrow(() -> validator.validate(paymentSucceeded("message-1", PAYMENT_ID, amount)));
        assertDoesNotThrow(() -> validator.validate(ticketReserved(EVENT_ID, amount)));
    }

    @Test
    void shouldRejectMissingPaymentTimestamp() {
        PaymentSucceededEvent event = new PaymentSucceededEvent("message-1", 123L, PAYMENT_ID, AMOUNT, null);

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(event))
                                            .withMessage("createdAt must not be null");
    }

    @Test
    void shouldRejectMissingCatalogEventId() {
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(ticketReserved(null, AMOUNT)))
                                            .withMessage("eventId must not be null");
    }

    @Test
    void shouldRejectMissingReservationId() {
        TicketConfirmedEvent event = new TicketConfirmedEvent("message-1", 123L, null, TICKET_ID, OCCURRED_AT);

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(event))
                                            .withMessage("reservationId must not be null");
    }

    @Test
    void shouldRejectMissingTicketId() {
        TicketConfirmedEvent event = new TicketConfirmedEvent("message-1", 123L, RESERVATION_ID, null, OCCURRED_AT);

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(event))
                                            .withMessage("ticketId must not be null");
    }

    @Test
    void shouldRejectMissingReservationTimestamp() {
        TicketReleasedEvent event = new TicketReleasedEvent("message-1", 123L, RESERVATION_ID, TICKET_ID, null);

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(event))
                                            .withMessage("occurredAt must not be null");
    }

    @Test
    void shouldRejectMissingConfirmationFailureReason() {
        TicketConfirmationFailedEvent event = new TicketConfirmationFailedEvent("message-1", 123L, RESERVATION_ID,
                TICKET_ID, null, OCCURRED_AT);

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(event))
                                            .withMessage("reason must not be null");
    }

    @ParameterizedTest
    @MethodSource("nullEventChecks")
    void shouldRejectNullEvent(Runnable validation) {
        assertThatIllegalArgumentException().isThrownBy(validation::run);
    }

    private static Stream<Runnable> nullEventChecks() {
        OrderResultEventValidator validator = new OrderResultEventValidator();
        return Stream.<Runnable>of(() -> validator.validate((PaymentSucceededEvent) null),
                                   () -> validator.validate((PaymentFailedEvent) null),
                                   () -> validator.validate((PaymentRefundedEvent) null),
                                   () -> validator.validate((TicketReservedEvent) null),
                                   () -> validator.validate((TicketReservationFailedEvent) null),
                                   () -> validator.validate((TicketReleasedEvent) null),
                                   () -> validator.validate((TicketConfirmedEvent) null),
                                   () -> validator.validate((TicketConfirmationFailedEvent) null));
    }

    private PaymentSucceededEvent paymentSucceeded(String messageId, UUID paymentId, BigDecimal amount) {
        return new PaymentSucceededEvent(messageId, 123L, paymentId, amount, OCCURRED_AT);
    }

    private TicketReservedEvent ticketReserved(UUID eventId, BigDecimal price) {
        return new TicketReservedEvent("reservation-success", 123L, RESERVATION_ID, TICKET_ID, eventId, price,
                OCCURRED_AT);
    }
}