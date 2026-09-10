package ru.ticketcraft.model;

import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketReservedEvent;

public enum OutboxEventType {

    TICKET_RESERVED("TicketReserved", TicketReservedEvent.class),

    TICKET_RESERVATION_FAILED("TicketReservationFailed", TicketReservationFailedEvent.class);

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

        throw new IllegalArgumentException("Unknown outbox event type: " + value);
    }
}