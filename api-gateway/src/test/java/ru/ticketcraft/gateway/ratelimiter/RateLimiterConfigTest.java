package ru.ticketcraft.gateway.ratelimiter;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RateLimiterConfigTest {

    private final RateLimiterConfig config = new RateLimiterConfig();

    @Test
    void shouldResolveUserKeyFromJwtSubject() {
        ServerWebExchange exchange = exchangeWithJwtSubject("user-123");

        StepVerifier.create(config.userKeyResolver().resolve(exchange)).expectNext("user:user-123").verifyComplete();
    }

    @Test
    void shouldReturnEmptyKeyWhenJwtSubjectIsMissing() {
        ServerWebExchange exchange = exchangeWithJwtSubject(null);

        StepVerifier.create(config.userKeyResolver().resolve(exchange)).verifyComplete();
    }

    @Test
    void shouldReturnEmptyKeyWhenJwtSubjectIsEmpty() {
        ServerWebExchange exchange = exchangeWithJwtSubject("");

        StepVerifier.create(config.userKeyResolver().resolve(exchange)).verifyComplete();
    }

    @Test
    void shouldReturnEmptyKeyWhenJwtSubjectIsBlank() {
        ServerWebExchange exchange = exchangeWithJwtSubject("   ");

        StepVerifier.create(config.userKeyResolver().resolve(exchange)).verifyComplete();
    }

    @Test
    void shouldReturnEmptyKeyWhenPrincipalIsMissing() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders/1"));

        StepVerifier.create(config.userKeyResolver().resolve(exchange)).verifyComplete();
    }

    @Test
    void shouldReturnEmptyKeyWhenPrincipalIsNotJwtAuthentication() {
        Authentication authentication = new UsernamePasswordAuthenticationToken("user", "password");

        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders/1"))
                                                          .mutate()
                                                          .principal(Mono.just(authentication))
                                                          .build();

        StepVerifier.create(config.userKeyResolver().resolve(exchange)).verifyComplete();
    }

    @Test
    void shouldResolveIpKeyFromRemoteAddress() {
        MockServerHttpRequest.BaseBuilder<?> request = 
                MockServerHttpRequest.get("/api/v1/catalog/events")
                                     .remoteAddress(new InetSocketAddress("192.0.2.10", 12345));

        ServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(config.ipKeyResolver().resolve(exchange)).expectNext("192.0.2.10").verifyComplete();
    }

    @Test
    void shouldIgnoreUntrustedForwardedForHeader() {
        MockServerHttpRequest.BaseBuilder<?> request = 
                MockServerHttpRequest.get("/api/v1/catalog/events")
                                     .remoteAddress(new InetSocketAddress("192.0.2.10", 12345))
                                     .header("X-Forwarded-For", "198.51.100.20");

        ServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(config.ipKeyResolver().resolve(exchange)).expectNext("192.0.2.10").verifyComplete();
    }

    @Test
    void shouldReturnUnknownWhenRemoteAddressIsMissing() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/catalog/events"));

        StepVerifier.create(config.ipKeyResolver().resolve(exchange)).expectNext("unknown").verifyComplete();
    }

    private ServerWebExchange exchangeWithJwtSubject(String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token").header("alg", "RS256").claim("test", true);

        if (subject != null) {
            builder.subject(subject);
        }

        JwtAuthenticationToken authentication = new JwtAuthenticationToken(builder.build());

        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders/1"))
                                    .mutate()
                                    .principal(Mono.just(authentication))
                                    .build();
    }
}