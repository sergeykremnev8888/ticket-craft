package ru.ticketcraft.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketcraft.kafka.topics")
public record KafkaTopicsProperties(String ticketReservationCommands, String ticketReservationResults) {
}