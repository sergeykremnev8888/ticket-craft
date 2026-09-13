package ru.ticketcraft.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableConfigurationProperties(KafkaTopicProperties.class)
public class KafkaTopicConfig {

    @Bean
    NewTopic orderEventsTopic(KafkaTopicProperties topicProperties) {
        return topic(
                topicProperties.getSourceTopic(),
                topicProperties);
    }

    @Bean
    NewTopic orderEventsDltTopic(KafkaTopicProperties topicProperties) {
        return topic(
                topicProperties.getDltTopic(),
                topicProperties);
    }

    private NewTopic topic(
            String name,
            KafkaTopicProperties properties) {

        return TopicBuilder.name(name)
                .partitions(properties.getPartitions())
                .replicas(properties.getReplicas())
                .build();
    }
}
