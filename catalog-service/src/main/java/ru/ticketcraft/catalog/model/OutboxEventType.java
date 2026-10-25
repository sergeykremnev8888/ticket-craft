package ru.ticketcraft.catalog.model;

public enum OutboxEventType {

    TICKET_RESERVED("TicketReserved"),
    TICKET_RESERVATION_FAILED("TicketReservationFailed"),
    TICKET_CONFIRMED("TicketConfirmed"),
    TICKET_CONFIRMATION_FAILED("TicketConfirmationFailed"),
    TICKET_RELEASED("TicketReleased");

    private final String value;

    OutboxEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}