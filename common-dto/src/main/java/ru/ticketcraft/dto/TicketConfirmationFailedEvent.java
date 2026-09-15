package ru.ticketcraft.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record TicketConfirmationFailedEvent(
        String messageId,
        Long orderId,
        UUID reservationId,
        UUID ticketId,
        TicketConfirmationFailureReason reason,
        Instant occurredAt) {

    @JsonCreator
    public TicketConfirmationFailedEvent(
            @JsonProperty("messageId") String messageId,
            @JsonProperty("orderId") Long orderId,
            @JsonProperty("reservationId") UUID reservationId,
            @JsonProperty("ticketId") UUID ticketId,
            @JsonProperty("reason") TicketConfirmationFailureReason reason,
            @JsonProperty("occurredAt") Instant occurredAt) {

        this.messageId = messageId;
        this.orderId = orderId;
        this.reservationId = reservationId;
        this.ticketId = ticketId;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }
}