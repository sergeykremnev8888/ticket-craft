package ru.ticketcraft.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic ticketReservationCommandsTopic(
            KafkaTopicsProperties topics,
            KafkaTopicProperties topicProperties) {

        return topic(
                topics.ticketReservationCommands(),
                topicProperties);
    }

    @Bean
    NewTopic ticketReservationCommandsDltTopic(
            KafkaTopicsProperties topics,
            KafkaTopicProperties topicProperties) {

        return topic(
                topics.ticketReservationCommandsDlt(),
                topicProperties);
    }

    @Bean
    NewTopic ticketReservationResultsTopic(
            KafkaTopicsProperties topics,
            KafkaTopicProperties topicProperties) {

        return topic(
                topics.ticketReservationResults(),
                topicProperties);
    }

    private NewTopic topic(
            String name,
            KafkaTopicProperties properties) {

        return TopicBuilder.name(name)
                .partitions(properties.partitions())
                .replicas(properties.replicas())
                .build();
    }
}
