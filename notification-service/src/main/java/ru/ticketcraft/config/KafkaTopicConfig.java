package ru.ticketcraft.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableConfigurationProperties(KafkaRetryProperties.class)
public class KafkaTopicConfig {

    @Bean
    NewTopic orderEventsDltTopic(KafkaRetryProperties retryProperties) {
        return TopicBuilder.name(retryProperties.getDltTopic()).partitions(1).replicas(1).build();
    }
}