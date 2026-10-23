package ru.ticketcraft.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.dto.RefundPaymentCommand;
import ru.ticketcraft.dto.TicketConfirmationFailureReason;

class PaymentCommandValidatorTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");

    private static final UUID PAYMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final PaymentCommandValidator validator = new PaymentCommandValidator();

    @ParameterizedTest
    @ValueSource(strings = { "0.01", "1", "100.00", "100.000", "99999999999999999.99" })
    void shouldAcceptValidAmounts(String value) {

        BigDecimal amount = new BigDecimal(value);

        assertDoesNotThrow(() -> validator.validate(paymentRequest("message-1", 123L, 10L, amount, CREATED_AT)));

        assertDoesNotThrow(() -> validator.validate(refundCommand("refund-1", 123L, PAYMENT_ID, amount)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "0", "-0.01", "-100", "0.001", "100.001", "100000000000000000.00" })
    void shouldRejectInvalidAmounts(String value) {

        BigDecimal amount = value == null ? null : new BigDecimal(value);

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(paymentRequest("message-1",
                                                                                                123L,
                                                                                                10L,
                                                                                                amount,
                                                                                                CREATED_AT)));

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(refundCommand("refund-1",
                                                                                               123L,
                                                                                               PAYMENT_ID,
                                                                                               amount)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t" })
    void shouldRejectBlankMessageId(String messageId) {

        assertInvalidMessageId(messageId);
    }

    @Test
    void shouldRejectMessageIdLongerThan128Characters() {

        assertInvalidMessageId("a".repeat(129));
    }

    @Test
    void shouldAcceptMessageIdWith128Characters() {
        String messageId = "a".repeat(128);

        assertDoesNotThrow(() -> validator.validate(paymentRequest(messageId,
                                                                   123L,
                                                                   10L,
                                                                   new BigDecimal("100.00"),
                                                                   CREATED_AT)));

        assertDoesNotThrow(() -> validator.validate(refundCommand(messageId,
                                                                  123L,
                                                                  PAYMENT_ID,
                                                                  new BigDecimal("100.00"))));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = { 0, -1 })
    void shouldRejectInvalidOrderId(Long orderId) {
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(paymentRequest("message-1",
                                                                                                orderId,
                                                                                                10L,
                                                                                                new BigDecimal(
                                                                                                        "100.00"),
                                                                                                CREATED_AT)))
                                            .withMessage("orderId must be positive");

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(refundCommand("refund-1",
                                                                                               orderId,
                                                                                               PAYMENT_ID,
                                                                                               new BigDecimal(
                                                                                                       "100.00"))))
                                            .withMessage("orderId must be positive");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = { 0, -1 })
    void shouldRejectInvalidUserId(Long userId) {
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(paymentRequest("message-1",
                                                                                                123L,
                                                                                                userId,
                                                                                                new BigDecimal(
                                                                                                        "100.00"),
                                                                                                CREATED_AT)))
                                            .withMessage("userId must be positive");
    }

    @Test
    void shouldRejectMissingPaymentRequestTimestamp() {

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(paymentRequest("message-1",
                                                                                                123L,
                                                                                                10L,
                                                                                                new BigDecimal(
                                                                                                        "100.00"),
                                                                                                null)))
                                            .withMessage("createdAt must not be null");
    }

    @Test
    void shouldRejectMissingRefundPaymentId() {
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(refundCommand("refund-1",
                                                                                               123L,
                                                                                               null,
                                                                                               new BigDecimal(
                                                                                                       "100.00"))))
                                            .withMessage("paymentId must not be null");
    }

    @Test
    void shouldRejectNullPaymentRequest() {
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate((PaymentRequestedEvent) null))
                                            .withMessage("Payment request must not be null");
    }

    @Test
    void shouldRejectNullRefundCommand() {
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate((RefundPaymentCommand) null))
                                            .withMessage("Refund command must not be null");
    }

    @Test
    void shouldNotChangeOriginalAmount() {
        BigDecimal amount = new BigDecimal("100.000");

        PaymentRequestedEvent event = paymentRequest("message-1", 123L, 10L, amount, CREATED_AT);

        validator.validate(event);

        assertThat(event.amount()).isSameAs(amount);
        assertThat(event.amount().scale()).isEqualTo(3);
    }

    @Test
    void shouldRejectMissingRefundReason() {
        RefundPaymentCommand command = new RefundPaymentCommand("refund-1", 123L, PAYMENT_ID, new BigDecimal("100.00"),
                null, CREATED_AT);

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(command))
                                            .withMessage("reason must not be null");
    }

    @Test
    void shouldRejectMissingRefundTimestamp() {
        RefundPaymentCommand command = new RefundPaymentCommand("refund-1", 123L, PAYMENT_ID, new BigDecimal("100.00"),
                TicketConfirmationFailureReason.RESERVATION_EXPIRED, null);

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(command))
                                            .withMessage("occurredAt must not be null");
    }

    private void assertInvalidMessageId(String messageId) {
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(paymentRequest(messageId,
                                                                                                123L,
                                                                                                10L,
                                                                                                new BigDecimal(
                                                                                                        "100.00"),
                                                                                                CREATED_AT)))
                                            .withMessage("messageId must contain between 1 and 128 characters");

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(refundCommand(messageId,
                                                                                               123L,
                                                                                               PAYMENT_ID,
                                                                                               new BigDecimal(
                                                                                                       "100.00"))))
                                            .withMessage("messageId must contain between 1 and 128 characters");
    }

    private PaymentRequestedEvent paymentRequest(String messageId,
                                                 Long orderId,
                                                 Long userId,
                                                 BigDecimal amount,
                                                 Instant createdAt) {
        return new PaymentRequestedEvent(messageId, orderId, userId, amount, createdAt);
    }

    private RefundPaymentCommand refundCommand(String messageId, Long orderId, UUID paymentId, BigDecimal amount) {
        return new RefundPaymentCommand(messageId, orderId, paymentId, amount,
                TicketConfirmationFailureReason.RESERVATION_EXPIRED, CREATED_AT);
    }
}