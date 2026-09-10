package ru.ticketcraft.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Команда на выполнение оплаты заказа.
 *
 * Событие публикуется order-service и потребляется payment-service.
 */
public record PaymentRequestedEvent(
        String messageId,
        Long orderId,
        Long userId,
        BigDecimal amount,
        Instant createdAt) {

    @JsonCreator
    public PaymentRequestedEvent(
            @JsonProperty("messageId") String messageId,
            @JsonProperty("orderId") Long orderId,
            @JsonProperty("userId") Long userId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("createdAt") Instant createdAt) {

        this.messageId = messageId;
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.createdAt = createdAt;
    }
}
