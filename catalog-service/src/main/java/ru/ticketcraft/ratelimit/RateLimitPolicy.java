package ru.ticketcraft.ratelimit;

public enum RateLimitPolicy {

    CATALOG_READ("catalog-read"),
    TICKET_RESERVATION("ticket-reservation");

    private final String key;

    RateLimitPolicy(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
