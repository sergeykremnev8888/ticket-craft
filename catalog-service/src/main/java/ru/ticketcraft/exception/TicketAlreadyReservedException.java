package ru.ticketcraft.exception;

public class TicketAlreadyReservedException extends RuntimeException {

    public TicketAlreadyReservedException(String message) {
        super(message);
    }
}
