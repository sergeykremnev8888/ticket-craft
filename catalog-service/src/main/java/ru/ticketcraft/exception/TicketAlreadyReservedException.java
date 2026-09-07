package ru.ticketcraft.exception;

public class TicketAlreadyReservedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TicketAlreadyReservedException(String message) {
        super(message);
    }
}
