package ru.ticketcraft.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record RefundPaymentCommand(
        String messageId,
        Long orderId,
        UUID paymentId,
        BigDecimal amount,
        TicketConfirmationFailureReason reason,
        Instant occurredAt) {

    @JsonCreator
    public RefundPaymentCommand(
            @JsonProperty("messageId") String messageId,
            @JsonProperty("orderId") Long orderId,
            @JsonProperty("paymentId") UUID paymentId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("reason") TicketConfirmationFailureReason reason,
            @JsonProperty("occurredAt") Instant occurredAt) {

        this.messageId = messageId;
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.amount = amount;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }
}