package ru.ticketcraft.exception;

public class InvalidOrderStateTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidOrderStateTransitionException(String message) {
        super(message);
    }
}
