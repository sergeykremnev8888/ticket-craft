package ru.ticketcraft.ratelimit;

import java.time.Duration;

public record RateLimitResult(
        boolean allowed,
        long limit,
        long remaining,
        Duration retryAfter) {

    public RateLimitResult {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (remaining < 0) {
            throw new IllegalArgumentException("remaining must not be negative");
        }
        if (retryAfter == null || retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be null or negative");
        }
    }
}
