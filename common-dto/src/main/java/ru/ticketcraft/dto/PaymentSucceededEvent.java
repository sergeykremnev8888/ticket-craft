package ru.ticketcraft.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Результат успешной оплаты заказа.
 *
 * Событие публикуется payment-service.
 */
public record PaymentSucceededEvent(
        String messageId,
        Long orderId,
        UUID paymentId,
        BigDecimal amount,
        Instant createdAt) {

    @JsonCreator
    public PaymentSucceededEvent(
            @JsonProperty("messageId") String messageId,
            @JsonProperty("orderId") Long orderId,
            @JsonProperty("paymentId") UUID paymentId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("createdAt") Instant createdAt) {

        this.messageId = messageId;
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.amount = amount;
        this.createdAt = createdAt;
    }
}
