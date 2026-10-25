package ru.ticketcraft.catalog.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

public interface ClientKeyResolver {

    String resolve(HttpServletRequest request);
}
