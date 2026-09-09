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
    NewTopic orderEventsDltTopic(KafkaTopicProperties topicProperties) {
        return TopicBuilder.name(topicProperties.getDltTopic()).partitions(topicProperties.getPartitions())
                .replicas(topicProperties.getReplicas()).build();
    }
}