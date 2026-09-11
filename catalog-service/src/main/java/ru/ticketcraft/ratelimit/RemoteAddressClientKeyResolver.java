package ru.ticketcraft.ratelimit;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class RemoteAddressClientKeyResolver implements ClientKeyResolver {

    private static final String UNKNOWN_CLIENT = "unknown";

    @Override
    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return UNKNOWN_CLIENT;
        }
        return remoteAddress;
    }
}
