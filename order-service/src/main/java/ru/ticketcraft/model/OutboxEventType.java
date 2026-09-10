package ru.ticketcraft.model;

import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.dto.ReleaseTicketCommand;
import ru.ticketcraft.dto.ReserveTicketCommand;

public enum OutboxEventType {

    ORDER_CREATED("OrderCreated", OrderEvent.class),

    RESERVE_TICKET("ReserveTicket", ReserveTicketCommand.class),

    PAYMENT_REQUESTED("PaymentRequested", PaymentRequestedEvent.class),

    RELEASE_TICKET("ReleaseTicket", ReleaseTicketCommand.class);

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

        throw new IllegalArgumentException("Unsupported outbox event type: " + value);
    }
}