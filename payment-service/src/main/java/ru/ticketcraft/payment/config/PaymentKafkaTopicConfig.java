package ru.ticketcraft.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;
import org.apache.kafka.clients.admin.NewTopic;

@Configuration
public class PaymentKafkaTopicConfig {

    private final PaymentKafkaProperties kafkaProperties;

    public PaymentKafkaTopicConfig(PaymentKafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    NewTopics paymentTopics() {
        NewTopic paymentRequests = TopicBuilder.name(kafkaProperties.getRequestTopic())
                .partitions(kafkaProperties.getPartitions()).replicas(kafkaProperties.getReplicas()).build();

        NewTopic paymentResults = TopicBuilder.name(kafkaProperties.getResultTopic())
                .partitions(kafkaProperties.getPartitions()).replicas(kafkaProperties.getReplicas()).build();

        NewTopic paymentRequestsDlt = TopicBuilder.name(kafkaProperties.getDltTopic())
                .partitions(kafkaProperties.getPartitions()).replicas(kafkaProperties.getReplicas()).build();

        return new NewTopics(paymentRequests, paymentResults, paymentRequestsDlt);
    }
}