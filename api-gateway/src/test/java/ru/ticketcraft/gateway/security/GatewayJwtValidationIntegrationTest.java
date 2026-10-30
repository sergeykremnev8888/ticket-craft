package ru.ticketcraft.gateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "ticketcraft.gateway.security.metrics-public=false")
@ActiveProfiles("test")
@AutoConfigureWebTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class GatewayJwtValidationIntegrationTest {

    private static final String METRICS_PATH = "/actuator/prometheus";
    private static final String AUDIENCE = "ticketcraft-api";
    private static final String CORRELATION_ID = "jwt-validation-test";

    private static final RSAKey PRIMARY_KEY = generateKey("primary-key");

    // Другой приватный ключ с тем же kid:
    // Gateway найдёт опубликованный ключ, но подпись не сойдётся.
    private static final RSAKey UNTRUSTED_KEY = generateKey("primary-key");

    private static final AtomicReference<String> PUBLISHED_JWKS = new AtomicReference<>(
            new JWKSet(PRIMARY_KEY.toPublicJWK()).toString());

    private static final AtomicInteger JWK_REQUESTS = new AtomicInteger();
    private static final AtomicBoolean JWK_AVAILABLE = new AtomicBoolean(true);

    private static final DisposableServer JWK_SERVER =
            HttpServer.create()
                      .host("127.0.0.1")
                      .port(0)
                      .route(routes -> routes.get("/jwks", (request, response) -> {
                          JWK_REQUESTS.incrementAndGet();

                          if (!JWK_AVAILABLE.get()) {
                              return response.status(503).sendString(Mono.just("JWK endpoint unavailable"));
                          }

                          return response.status(200)
                                         .header(HttpHeaders.CONTENT_TYPE, "application/json")
                                         .sendString(Mono.just(PUBLISHED_JWKS.get()));
                      }))
                      .bindNow();

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void configureJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                     GatewayJwtValidationIntegrationTest::issuer);

        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                     () -> "http://127.0.0.1:" + JWK_SERVER.port() + "/jwks");

        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> AUDIENCE);
    }

    @AfterAll
    static void stopJwkServer() {
        JWK_SERVER.disposeNow();
    }

    @Test
    void shouldAcceptValidSignedToken() throws JOSEException {
        assertAccepted(validToken(PRIMARY_KEY));
    }

    @Test
    void shouldRejectInvalidSignature() throws JOSEException {
        assertUnauthorized(validToken(UNTRUSTED_KEY));
    }

    @Test
    void shouldRejectExpiredToken() throws JOSEException {
        Instant now = Instant.now();

        // Истёк значительно раньше допустимого clock skew.
        String token = createToken(PRIMARY_KEY, issuer(), AUDIENCE, now.minusSeconds(600), now.minusSeconds(300));

        assertUnauthorized(token);
    }

    @Test
    void shouldRejectWrongIssuer() throws JOSEException {
        Instant now = Instant.now();

        String token = createToken(PRIMARY_KEY,
                                   "https://untrusted.example/realm",
                                   AUDIENCE,
                                   now.minusSeconds(10),
                                   now.plusSeconds(300));

        assertUnauthorized(token);
    }

    @Test
    void shouldRejectWrongAudience() throws JOSEException {
        Instant now = Instant.now();

        String token = createToken(PRIMARY_KEY, issuer(), "another-api", now.minusSeconds(10), now.plusSeconds(300));

        assertUnauthorized(token);
    }

    @Test
    void shouldRefreshJwkSetWhenNewSigningKeyAppears() throws JOSEException {
        // Сначала проверяем токен старого ключа:
        // decoder получает или использует кеш публичных ключей.
        assertAccepted(validToken(PRIMARY_KEY));

        RSAKey rotatedKey = generateKey("rotated-" + UUID.randomUUID());

        // Во время ротации публикуем оба публичных ключа.
        PUBLISHED_JWKS.set(new JWKSet(List.of(PRIMARY_KEY.toPublicJWK(), rotatedKey.toPublicJWK())).toString());

        int requestsBefore = JWK_REQUESTS.get();

        // Неизвестный kid должен вызвать обновление набора ключей.
        assertAccepted(validToken(rotatedKey));

        assertTrue(JWK_REQUESTS.get() > requestsBefore, "Decoder must request JWK Set for a new kid");

        // Старый ключ пока опубликован — его токены тоже принимаются.
        assertAccepted(validToken(PRIMARY_KEY));
    }

    @Test
    void shouldAcceptTokenWithCachedKeyWhenJwkEndpointIsUnavailable() throws JOSEException {
        // Загружаем публичный ключ в настоящий decoder.
        assertAccepted(validToken(PRIMARY_KEY));

        int requestsBefore = JWK_REQUESTS.get();

        JWK_AVAILABLE.set(false);
        try {
            // Создаём новый токен, подписанный тем же ключом.
            // Проверяется кеш ключей, а не повторное использование одного JWT.
            assertAccepted(validToken(PRIMARY_KEY));

            assertEquals(requestsBefore,
                         JWK_REQUESTS.get(),
                         "Decoder must not request to JWK endpoint for known key");
        } finally {
            JWK_AVAILABLE.set(true);
        }
    }

    @Test
    void shouldRejectUnknownKeyDuringJwkOutageAndRecoverAfterRestoration() throws JOSEException {
        assertAccepted(validToken(PRIMARY_KEY));

        RSAKey newKey = generateKey("outage-" + UUID.randomUUID());
        String token = validToken(newKey);

        int requestsBefore = JWK_REQUESTS.get();

        JWK_AVAILABLE.set(false);
        try {
            client.mutate()
                  .responseTimeout(Duration.ofSeconds(5))
                  .build()
                  .get()
                  .uri(METRICS_PATH)
                  .headers(headers -> headers.setBearerAuth(token))
                  .header("X-Correlation-ID", CORRELATION_ID)
                  .exchange()
                  .expectStatus()
                  .is5xxServerError()
                  .expectHeader()
                  .valueEquals("X-Correlation-ID", CORRELATION_ID)
                  .expectBody();

            assertTrue(JWK_REQUESTS.get() > requestsBefore, "Unknown kid must request a set of public keys");

            // Ошибка обновления не должна лишать нас уже загруженного ключа.
            assertAccepted(validToken(PRIMARY_KEY));
        } finally {
            // Публикуем новый ключ до восстановления доступности.
            PUBLISHED_JWKS.set(new JWKSet(List.of(PRIMARY_KEY.toPublicJWK(), newKey.toPublicJWK())).toString());

            JWK_AVAILABLE.set(true);
        }

        int requestsBeforeRecovery = JWK_REQUESTS.get();

        // Тот же токен теперь должен пройти без перезапуска Gateway.
        assertAccepted(token);

        assertTrue(JWK_REQUESTS.get() > requestsBeforeRecovery,
                   "When decoder is recovered then it must get keys");
    }

    private void assertAccepted(String token) {
        client.get()
              .uri(METRICS_PATH)
              .headers(headers -> headers.setBearerAuth(token))
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody();
    }

    private void assertUnauthorized(String token) {
        client.get()
              .uri(METRICS_PATH)
              .headers(headers -> headers.setBearerAuth(token))
              .header("X-Correlation-ID", CORRELATION_ID)
              .exchange()
              .expectStatus()
              .isUnauthorized()
              .expectHeader()
              .valueMatches(HttpHeaders.WWW_AUTHENTICATE, ".*error=\"invalid_token\".*")
              .expectHeader()
              .valueEquals("X-Correlation-ID", CORRELATION_ID)
              .expectBody()
              .jsonPath("$.status")
              .isEqualTo(401)
              .jsonPath("$.code")
              .isEqualTo("UNAUTHORIZED")
              .jsonPath("$.instance")
              .isEqualTo(METRICS_PATH)
              .jsonPath("$.correlationId")
              .isEqualTo(CORRELATION_ID);
    }

    private static String validToken(RSAKey signingKey) throws JOSEException {
        Instant now = Instant.now();

        return createToken(signingKey, issuer(), AUDIENCE, now.minusSeconds(10), now.plusSeconds(300));
    }

    private static String createToken(RSAKey signingKey,
                                      String tokenIssuer,
                                      String audience,
                                      Instant issuedAt,
                                      Instant expiresAt) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(tokenIssuer)
                                                        .subject("test-user")
                                                        .audience(audience)
                                                        .issueTime(Date.from(issuedAt))
                                                        .expirationTime(Date.from(expiresAt))
                                                        .jwtID(UUID.randomUUID().toString())
                                                        .claim("scope", "metrics.read")
                                                        .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                                                                    .keyID(signingKey.getKeyID())
                                                                    .build();

        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(signingKey));

        return jwt.serialize();
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to create a RSA-key", exception);
        }
    }

    private static String issuer() {
        return "http://127.0.0.1:" + JWK_SERVER.port() + "/realms/test";
    }
}