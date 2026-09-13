package ru.ticketcraft.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketcraft.kafka.consumer")
public record KafkaConsumerProperties(
        int concurrency,
        String ticketReservationResultsGroupId,
        String paymentResultsGroupId) {

    public KafkaConsumerProperties {
        if (concurrency < 1) {
            throw new IllegalArgumentException("Kafka consumer concurrency must be at least 1");
        }

        if (ticketReservationResultsGroupId == null
                || ticketReservationResultsGroupId.isBlank()) {
            throw new IllegalArgumentException(
                    "Ticket reservation results group id must not be blank");
        }

        if (paymentResultsGroupId == null
                || paymentResultsGroupId.isBlank()) {
            throw new IllegalArgumentException(
                    "Payment results group id must not be blank");
        }
    }
}
