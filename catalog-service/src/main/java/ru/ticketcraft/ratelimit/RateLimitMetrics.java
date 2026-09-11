package ru.ticketcraft.ratelimit;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class RateLimitMetrics {

    private final Map<RateLimitPolicy, Counter> allowedCounters;
    private final Map<RateLimitPolicy, Counter> rejectedCounters;
    private final Map<RateLimitPolicy, Counter> errorCounters;

    public RateLimitMetrics(MeterRegistry meterRegistry) {
        allowedCounters = createCounters(
                meterRegistry,
                "ticketcraft.ratelimit.allowed",
                "Allowed requests by rate-limit policy");

        rejectedCounters = createCounters(
                meterRegistry,
                "ticketcraft.ratelimit.rejected",
                "Rejected requests by rate-limit policy");

        errorCounters = createCounters(
                meterRegistry,
                "ticketcraft.ratelimit.errors",
                "Rate limiter errors by policy");
    }

    public void recordAllowed(RateLimitPolicy policy) {
        allowedCounters.get(policy).increment();
    }

    public void recordRejected(RateLimitPolicy policy) {
        rejectedCounters.get(policy).increment();
    }

    public void recordError(RateLimitPolicy policy) {
        errorCounters.get(policy).increment();
    }

    private static Map<RateLimitPolicy, Counter> createCounters(
            MeterRegistry meterRegistry,
            String metricName,
            String description) {

        Map<RateLimitPolicy, Counter> counters =
                new EnumMap<>(RateLimitPolicy.class);

        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            Counter counter = Counter.builder(metricName)
                    .description(description)
                    .tag("policy", policy.key())
                    .register(meterRegistry);

            counters.put(policy, counter);
        }

        return counters;
    }
}
