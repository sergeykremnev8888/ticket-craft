package ru.ticketcraft.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketcraft.kafka.retry")
public class PaymentRetryProperties {

    private int maxAttempts = 3;
    private Duration backOff = Duration.ofSeconds(1);

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getBackOff() {
        return backOff;
    }

    public void setBackOff(Duration backOff) {
        this.backOff = backOff;
    }
}