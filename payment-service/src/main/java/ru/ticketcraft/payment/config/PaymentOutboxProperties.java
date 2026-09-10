package ru.ticketcraft.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketcraft.outbox")
public class PaymentOutboxProperties {

    private int batchSize = 100;

    private Duration claimLease = Duration.ofSeconds(30);

    private Duration publishDelay = Duration.ofSeconds(1);

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getClaimLease() {
        return claimLease;
    }

    public void setClaimLease(Duration claimLease) {
        this.claimLease = claimLease;
    }

    public Duration getPublishDelay() {
        return publishDelay;
    }

    public void setPublishDelay(Duration publishDelay) {
        this.publishDelay = publishDelay;
    }
}