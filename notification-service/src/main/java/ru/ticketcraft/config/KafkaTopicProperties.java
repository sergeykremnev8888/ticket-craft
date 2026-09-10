package ru.ticketcraft.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@ConfigurationProperties(prefix = "ticketcraft.kafka.topic")
@Validated
public class KafkaTopicProperties {

    @NotBlank
    private String sourceTopic;

    @NotBlank
    private String dltTopic;

    @Min(1)
    private int partitions = 1;

    @Min(1)
    private int replicas = 1;

    public String getSourceTopic() {
        return sourceTopic;
    }

    public void setSourceTopic(String sourceTopic) {
        this.sourceTopic = sourceTopic;
    }

    public String getDltTopic() {
        return dltTopic;
    }

    public void setDltTopic(String dltTopic) {
        this.dltTopic = dltTopic;
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