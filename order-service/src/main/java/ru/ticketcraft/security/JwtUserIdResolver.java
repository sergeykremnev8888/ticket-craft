package ru.ticketcraft.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class JwtUserIdResolver {

    static final String USER_ID_CLAIM = "user_id";

    public Long resolve(Jwt jwt) {

        Object claim = jwt.getClaims().get(USER_ID_CLAIM);

        if (claim instanceof Number number) {
            long userId = number.longValue();
            return requirePositive(userId);
        }

        if (claim instanceof String value) {
            try {
                return requirePositive(Long.parseLong(value));
            } catch (NumberFormatException ex) {
                throw invalidUserIdClaim();
            }
        }

        throw invalidUserIdClaim();
    }

    private static Long requirePositive(long userId) {

        if (userId <= 0) {
            throw invalidUserIdClaim();
        }

        return userId;
    }

    private static AccessDeniedException invalidUserIdClaim() {
        return new AccessDeniedException("JWT claim 'user_id' must contain a positive numeric user identifier");
    }
}
