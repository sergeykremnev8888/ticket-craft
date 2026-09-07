package ru.ticketcraft.exception;

public class OrderConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OrderConflictException(String message) {
        super(message);
    }
}
