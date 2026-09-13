package ru.ticketcraft.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@ConfigurationProperties(prefix = "ticketcraft.kafka")
@Validated
public class PaymentKafkaProperties {

    @NotBlank
    private String requestTopic;

    @NotBlank
    private String resultTopic;

    @NotBlank
    private String dltTopic;

    @Min(1)
    private int concurrency = 1;

    @NotNull
    private Duration pollTimeout = Duration.ofSeconds(1);

    @Min(1)
    private int partitions = 1;

    @Min(1)
    private int replicas = 1;

    public String getRequestTopic() {
        return requestTopic;
    }

    public void setRequestTopic(String requestTopic) {
        this.requestTopic = requestTopic;
    }

    public String getResultTopic() {
        return resultTopic;
    }

    public void setResultTopic(String resultTopic) {
        this.resultTopic = resultTopic;
    }

    public String getDltTopic() {
        return dltTopic;
    }

    public void setDltTopic(String dltTopic) {
        this.dltTopic = dltTopic;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public Duration getPollTimeout() {
        return pollTimeout;
    }

    public void setPollTimeout(Duration pollTimeout) {
        this.pollTimeout = pollTimeout;
    }

    public int getPartitions() {
        return partitions;
    }

    public void setPartitions(int partitions) {
        this.partitions = partitions;
    }

    public int getReplicas() {
        return replicas;
    }

    public void setReplicas(int replicas) {
        this.replicas = replicas;
    }
}
