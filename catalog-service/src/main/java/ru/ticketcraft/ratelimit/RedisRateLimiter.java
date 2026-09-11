package ru.ticketcraft.ratelimit;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisRateLimiter {

    private static final String KEY_PREFIX = "ticketcraft:ratelimit:";

    private static final String TOKEN_BUCKET_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_tokens = tonumber(ARGV[2])
            local refill_period_ms = tonumber(ARGV[3])
            local ttl_ms = tonumber(ARGV[4])

            local redis_time = redis.call('TIME')
            local now_ms = tonumber(redis_time[1]) * 1000
                + math.floor(tonumber(redis_time[2]) / 1000)

            local state = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
            local tokens = tonumber(state[1])
            local last_refill_ms = tonumber(state[2])

            if tokens == nil then
                tokens = capacity
            end

            if last_refill_ms == nil then
                last_refill_ms = now_ms
            end

            if now_ms > last_refill_ms then
                local elapsed_ms = now_ms - last_refill_ms
                local refilled = elapsed_ms * refill_tokens / refill_period_ms
                tokens = math.min(capacity, tokens + refilled)
            end

            local allowed = 0
            local retry_after_ms = 0

            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            else
                local missing_tokens = 1 - tokens
                retry_after_ms = math.ceil(
                    missing_tokens * refill_period_ms / refill_tokens
                )
            end

            redis.call(
                'HSET',
                key,
                'tokens', tokens,
                'last_refill_ms', now_ms
            )
            redis.call('PEXPIRE', key, ttl_ms)

            return {
                allowed,
                math.floor(tokens),
                retry_after_ms
            }
            """;

    private static final RedisScript<List<?>> SCRIPT = createScript();

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    public RedisRateLimiter(
            StringRedisTemplate redisTemplate,
            RateLimitProperties properties) {

        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public RateLimitResult acquire(
            RateLimitPolicy policy,
            String clientKey) {

        RateLimitProperties.Policy policyProperties =
                properties.policyFor(policy);

        long refillPeriodMillis =
                policyProperties.refillPeriod().toMillis();

        long ttlMillis = calculateBucketTtlMillis(policyProperties);

        String redisKey = KEY_PREFIX + policy.key() + ":" + clientKey;

        List<?> scriptResult = redisTemplate.execute(
                SCRIPT,
                Collections.singletonList(redisKey),
                Long.toString(policyProperties.capacity()),
                Long.toString(policyProperties.refillTokens()),
                Long.toString(refillPeriodMillis),
                Long.toString(ttlMillis));

        if (scriptResult == null || scriptResult.size() != 3) {
            throw new IllegalStateException(
                    "Unexpected Redis rate-limit script result");
        }

        boolean allowed = numberAt(scriptResult, 0) == 1L;
        long remaining = Math.max(0L, numberAt(scriptResult, 1));
        long retryAfterMillis = Math.max(0L, numberAt(scriptResult, 2));

        return new RateLimitResult(
                allowed,
                policyProperties.capacity(),
                remaining,
                Duration.ofMillis(retryAfterMillis));
    }

    private static long numberAt(List<?> values, int index) {
        Object value = values.get(index);

        if (value instanceof Number number) {
            return number.longValue();
        }

        if (value instanceof String stringValue) {
            return Long.parseLong(stringValue);
        }

        throw new IllegalStateException(
                "Unexpected Redis rate-limit result value: " + value);
    }

    private static long calculateBucketTtlMillis(
            RateLimitProperties.Policy policy) {

        long refillPeriodMillis = policy.refillPeriod().toMillis();

        double timeToFull =
                (double) policy.capacity()
                        * refillPeriodMillis
                        / policy.refillTokens();

        long timeToFullMillis = (long) Math.ceil(timeToFull);

        return Math.max(
                refillPeriodMillis * 2,
                timeToFullMillis * 2);
    }

    private static RedisScript<List<?>> createScript() {
        DefaultRedisScript<List<?>> script = new DefaultRedisScript<>();
        script.setScriptText(TOKEN_BUCKET_SCRIPT);

        @SuppressWarnings("unchecked")
        Class<List<?>> resultType = (Class<List<?>>) (Class<?>) List.class;
        script.setResultType(resultType);

        return script;
    }
}
