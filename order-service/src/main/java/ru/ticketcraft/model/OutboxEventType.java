package ru.ticketcraft.model;

import ru.ticketcraft.common.dto.ConfirmTicketCommand;
import ru.ticketcraft.common.dto.OrderEvent;
import ru.ticketcraft.common.dto.PaymentRequestedEvent;
import ru.ticketcraft.common.dto.RefundPaymentCommand;
import ru.ticketcraft.common.dto.ReleaseTicketCommand;
import ru.ticketcraft.common.dto.ReserveTicketCommand;

public enum OutboxEventType {

    ORDER_CONFIRMED(
            "OrderConfirmed",
            OrderEvent.class),

    RESERVE_TICKET(
            "ReserveTicket",
            ReserveTicketCommand.class),

    PAYMENT_REQUESTED(
            "PaymentRequested",
            PaymentRequestedEvent.class),

    CONFIRM_TICKET(
            "ConfirmTicket",
            ConfirmTicketCommand.class),

    REFUND_PAYMENT(
            "RefundPayment",
            RefundPaymentCommand.class),

    RELEASE_TICKET(
            "ReleaseTicket",
            ReleaseTicketCommand.class);

    private final String value;
    private final Class<?> payloadType;

    OutboxEventType(String value, Class<?> payloadType) {
        this.value = value;
        this.payloadType = payloadType;
    }

    public String getValue() {
        return value;
    }

    public Class<?> getPayloadType() {
        return payloadType;
    }

    public static OutboxEventType fromValue(String value) {

        for (OutboxEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }

        throw new IllegalArgumentException(
                "Unsupported outbox event type: " + value);
    }
}