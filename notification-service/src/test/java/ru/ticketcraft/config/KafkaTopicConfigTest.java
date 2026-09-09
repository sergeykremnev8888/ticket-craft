package ru.ticketcraft.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

class KafkaTopicConfigTest {

    @Test
    void shouldCreateDltTopicWithConfiguredTopology() {
        KafkaRetryProperties retryProperties = new KafkaRetryProperties();
        retryProperties.setMaxAttempts(3);
        retryProperties.setBackoffMs(1000L);

        KafkaTopicProperties topicProperties = new KafkaTopicProperties();
        topicProperties.setPartitions(3);
        topicProperties.setReplicas((short) 1);
        topicProperties.setSourceTopic("order-events");
        topicProperties.setDltTopic("order-events.DLT");

        KafkaTopicConfig config = new KafkaTopicConfig();

        NewTopic topic = config.orderEventsDltTopic(topicProperties);

        assertThat(topic.name()).isEqualTo("order-events.DLT");
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }
}