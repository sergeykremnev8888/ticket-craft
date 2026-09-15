package ru.ticketcraft.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;

@Configuration
public class PaymentKafkaTopicConfig {

    private final PaymentKafkaProperties kafkaProperties;

    public PaymentKafkaTopicConfig(PaymentKafkaProperties kafkaProperties) {

        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    NewTopics paymentTopics() {

        NewTopic paymentRequests = topic(kafkaProperties.getRequestTopic());

        NewTopic paymentCommands = topic(kafkaProperties.getCommandTopic());

        NewTopic paymentResults = topic(kafkaProperties.getResultTopic());

        NewTopic paymentRequestsDlt = topic(kafkaProperties.getDltTopic());

        NewTopic paymentCommandsDlt = topic(kafkaProperties.getCommandDltTopic());

        return new NewTopics(paymentRequests, paymentCommands, paymentResults, paymentRequestsDlt, paymentCommandsDlt);
    }

    private NewTopic topic(String name) {

        return TopicBuilder.name(name).partitions(kafkaProperties.getPartitions())
                .replicas(kafkaProperties.getReplicas()).build();
    }
}