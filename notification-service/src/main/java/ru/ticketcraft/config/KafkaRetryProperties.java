package ru.ticketcraft.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

@ConfigurationProperties(prefix = "ticketcraft.kafka.retry")
@Validated
public class KafkaRetryProperties {

    @Min(1)
    private int maxAttempts;

    @Min(0)
    private long backoffMs;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getBackoffMs() {
        return backoffMs;
    }

    public void setBackoffMs(long backoffMs) {
        this.backoffMs = backoffMs;
    }

}