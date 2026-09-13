package ru.ticketcraft.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketcraft.kafka.consumer")
public record KafkaConsumerProperties(int concurrency) {

    public KafkaConsumerProperties {
        if (concurrency < 1) {
            throw new IllegalArgumentException("Kafka consumer concurrency must be at least 1");
        }
    }
}
