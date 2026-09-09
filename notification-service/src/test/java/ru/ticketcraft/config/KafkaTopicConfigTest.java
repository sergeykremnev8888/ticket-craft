package ru.ticketcraft.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

class KafkaTopicConfigTest {

    @Test
    void shouldCreateDltTopicFromConfiguration() {
        KafkaRetryProperties properties = new KafkaRetryProperties();
        properties.setMaxAttempts(3);
        properties.setBackoffMs(1000L);
        properties.setDltTopic("order-events.DLT");

        KafkaTopicConfig config = new KafkaTopicConfig();

        NewTopic topic = config.orderEventsDltTopic(properties);

        assertThat(topic.name()).isEqualTo("order-events.DLT");
        assertThat(topic.numPartitions()).isEqualTo(1);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }
}