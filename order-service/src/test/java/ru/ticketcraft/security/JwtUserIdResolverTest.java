package ru.ticketcraft.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtUserIdResolverTest {

    private final JwtUserIdResolver resolver = new JwtUserIdResolver();

    @Test
    void shouldResolveNumericUserIdClaim() {
        Jwt jwt = jwt(Map.of("user_id", 42L));

        assertThat(resolver.resolve(jwt)).isEqualTo(42L);
    }

    @Test
    void shouldResolveNumericStringUserIdClaim() {
        Jwt jwt = jwt(Map.of("user_id", "42"));

        assertThat(resolver.resolve(jwt)).isEqualTo(42L);
    }

    @Test
    void shouldRejectMissingUserIdClaim() {
        Jwt jwt = jwt(Map.of());

        assertThatThrownBy(() -> resolver.resolve(jwt))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("user_id");
    }

    @Test
    void shouldRejectNonPositiveUserIdClaim() {
        Jwt jwt = jwt(Map.of("user_id", 0L));

        assertThatThrownBy(() -> resolver.resolve(jwt))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("user_id");
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Map<String, Object> tokenClaims = new HashMap<>();
        tokenClaims.put("sub", "test-user");
        tokenClaims.putAll(claims);

        return new Jwt(
                "token",
                Instant.parse("2026-09-13T09:00:00Z"),
                Instant.parse("2026-09-13T10:00:00Z"),
                Map.of("alg", "RS256"),
                tokenClaims);
    }
}
