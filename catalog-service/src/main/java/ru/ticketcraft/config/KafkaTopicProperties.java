package ru.ticketcraft.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketcraft.kafka.topic")
public record KafkaTopicProperties(int partitions, int replicas) {

    public KafkaTopicProperties {
        if (partitions < 1) {
            throw new IllegalArgumentException("Kafka topic partitions must be at least 1");
        }

        if (replicas < 1) {
            throw new IllegalArgumentException("Kafka topic replicas must be at least 1");
        }
    }
}
