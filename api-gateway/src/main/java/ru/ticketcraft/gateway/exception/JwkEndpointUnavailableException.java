package ru.ticketcraft.gateway.exception;

/**
 * Не удалось получить публичные ключи для проверки JWT.
 */
public class JwkEndpointUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JwkEndpointUnavailableException(Throwable cause) {
        super("Unable to retrieve JWT verification keys", cause);
    }
}