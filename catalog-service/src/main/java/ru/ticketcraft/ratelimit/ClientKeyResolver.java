package ru.ticketcraft.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

public interface ClientKeyResolver {

    String resolve(HttpServletRequest request);
}
