package ru.ticketcraft.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import ru.ticketcraft.support.CatalogTestContainersConfiguration;

@SpringBootTest(properties = {
        "ticketcraft.outbox.publisher.enabled=false",
        "ticketcraft.rate-limit.enabled=true",

        "ticketcraft.rate-limit.catalog-read.capacity=10",
        "ticketcraft.rate-limit.catalog-read.refill-tokens=10",
        "ticketcraft.rate-limit.catalog-read.refill-period=1h",

        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.enabled=false"
})
@Import(CatalogTestContainersConfiguration.class)
class RedisRateLimiterIntegrationTest {

    private static final String RATE_LIMIT_KEY_PREFIX = "ticketcraft:ratelimit:";

    private static final String RATE_LIMIT_KEY_PATTERN = RATE_LIMIT_KEY_PREFIX + "*";

    @Autowired
    private RedisRateLimiter rateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        clearRateLimitKeys();
    }

    @AfterEach
    void cleanUp() {
        clearRateLimitKeys();
    }

    @Test
    void shouldAllowRequestsUntilCapacityAndThenReject() {
        String clientKey = uniqueClientKey();

        for (int expectedRemaining = 9; expectedRemaining >= 0; expectedRemaining--) {
            RateLimitResult result = rateLimiter.acquire(RateLimitPolicy.CATALOG_READ, clientKey);

            assertThat(result.allowed()).isTrue();
            assertThat(result.limit()).isEqualTo(10);
            assertThat(result.remaining()).isEqualTo(expectedRemaining);
            assertThat(result.retryAfter()).isZero();
        }

        RateLimitResult rejected = rateLimiter.acquire(RateLimitPolicy.CATALOG_READ, clientKey);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.limit()).isEqualTo(10);
        assertThat(rejected.remaining()).isZero();

        assertThat(rejected.retryAfter().toMillis()).isGreaterThan(0L)
                .isLessThanOrEqualTo(Duration.ofHours(1).toMillis());
    }

    @Test
    void shouldRefillTokensAfterEnoughTimeHasElapsed() {
        String clientKey = uniqueClientKey();

        /*
         * Полностью исчерпываем bucket:
         *
         * capacity = 10 refill = 10 tokens / 1 hour
         *
         * То есть один token восстанавливается примерно каждые 6 минут.
         */
        for (int i = 0; i < 10; i++) {
            RateLimitResult result = rateLimiter.acquire(RateLimitPolicy.CATALOG_READ, clientKey);

            assertThat(result.allowed()).isTrue();
        }

        RateLimitResult rejected = rateLimiter.acquire(RateLimitPolicy.CATALOG_READ, clientKey);

        assertThat(rejected.allowed()).isFalse();

        String redisKey = redisKey(RateLimitPolicy.CATALOG_READ, clientKey);

        /*
         * Проверяем refill детерминированно без Thread.sleep().
         *
         * 10 tokens / 60 minutes = 1 token / 6 minutes.
         *
         * За 13 минут должно восстановиться чуть больше 2 tokens. После consume одного
         * token remaining должен быть 1.
         */
        redisTemplate.opsForHash().put(redisKey, "tokens", "0");

        long thirteenMinutesAgo = Instant.now().minus(Duration.ofMinutes(13)).toEpochMilli();

        redisTemplate.opsForHash().put(redisKey, "last_refill_ms", Long.toString(thirteenMinutesAgo));

        RateLimitResult afterRefill = rateLimiter.acquire(RateLimitPolicy.CATALOG_READ, clientKey);

        assertThat(afterRefill.allowed()).isTrue();
        assertThat(afterRefill.limit()).isEqualTo(10);
        assertThat(afterRefill.remaining()).isEqualTo(1);
        assertThat(afterRefill.retryAfter()).isZero();
    }

    @Test
    void shouldSetExpirationOnBucketKey() {
        String clientKey = uniqueClientKey();

        rateLimiter.acquire(RateLimitPolicy.CATALOG_READ, clientKey);

        String redisKey = redisKey(RateLimitPolicy.CATALOG_READ, clientKey);

        Boolean exists = redisTemplate.hasKey(redisKey);

        assertThat(exists).isTrue();

        Long ttlMillis = redisTemplate.getExpire(redisKey, TimeUnit.MILLISECONDS);

        assertThat(ttlMillis).isNotNull().isPositive();

        /*
         * catalog-read:
         *
         * capacity = 10 refillTokens = 10 refillPeriod = 1h
         *
         * Полное восстановление bucket занимает 1 час.
         *
         * Текущая RedisRateLimiter implementation устанавливает bucket TTL максимум на
         * 2 часа для этой policy.
         */
        assertThat(ttlMillis).isLessThanOrEqualTo(Duration.ofHours(2).toMillis());
    }

    @Test
    void shouldKeepDifferentClientsIndependent() {
        String firstClient = uniqueClientKey();
        String secondClient = uniqueClientKey();

        for (int i = 0; i < 10; i++) {
            RateLimitResult result = rateLimiter.acquire(RateLimitPolicy.CATALOG_READ, firstClient);

            assertThat(result.allowed()).isTrue();
        }

        RateLimitResult firstClientRejected = rateLimiter.acquire(RateLimitPolicy.CATALOG_READ, firstClient);

        assertThat(firstClientRejected.allowed()).isFalse();

        RateLimitResult secondClientResult = rateLimiter.acquire(RateLimitPolicy.CATALOG_READ, secondClient);

        assertThat(secondClientResult.allowed()).isTrue();
        assertThat(secondClientResult.remaining()).isEqualTo(9);
    }

    @Test
    void shouldNeverAllowMoreThanCapacityUnderConcurrency() throws Exception {
        String clientKey = uniqueClientKey();
        int requestCount = 100;

        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    start.await();

                    RateLimitResult result = rateLimiter.acquire(RateLimitPolicy.CATALOG_READ, clientKey);

                    return result.allowed();
                }));
            }

            start.countDown();

            int allowed = 0;
            int rejected = 0;

            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    allowed++;
                } else {
                    rejected++;
                }
            }

            assertThat(allowed).isEqualTo(10);
            assertThat(rejected).isEqualTo(90);
        } finally {
            executor.shutdownNow();
        }
    }

    private String redisKey(RateLimitPolicy policy, String clientKey) {
        return RATE_LIMIT_KEY_PREFIX + policy.key() + ":" + clientKey;
    }

    private void clearRateLimitKeys() {
        Set<String> keys = redisTemplate.keys(RATE_LIMIT_KEY_PATTERN);

        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private String uniqueClientKey() {
        return "integration-test-" + UUID.randomUUID();
    }
}