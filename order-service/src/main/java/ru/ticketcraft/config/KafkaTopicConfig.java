package ru.ticketcraft.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic orderEventsTopic(
            KafkaTopicsProperties topics,
            KafkaTopicProperties topicProperties) {

        return topic(topics.orderEvents(), topicProperties);
    }

    @Bean
    NewTopic ticketReservationCommandsTopic(
            KafkaTopicsProperties topics,
            KafkaTopicProperties topicProperties) {

        return topic(
                topics.ticketReservationCommands(),
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

    @Bean
    NewTopic ticketReservationResultsDltTopic(
            KafkaTopicsProperties topics,
            KafkaTopicProperties topicProperties) {

        return topic(
                topics.ticketReservationResultsDlt(),
                topicProperties);
    }

    @Bean
    NewTopic paymentRequestsTopic(
            KafkaTopicsProperties topics,
            KafkaTopicProperties topicProperties) {

        return topic(topics.paymentRequests(), topicProperties);
    }

    @Bean
    NewTopic paymentResultsTopic(
            KafkaTopicsProperties topics,
            KafkaTopicProperties topicProperties) {

        return topic(topics.paymentResults(), topicProperties);
    }

    @Bean
    NewTopic paymentResultsDltTopic(
            KafkaTopicsProperties topics,
            KafkaTopicProperties topicProperties) {

        return topic(
                topics.paymentResultsDlt(),
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
