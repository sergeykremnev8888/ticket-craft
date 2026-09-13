package ru.ticketcraft.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

class KafkaTopicConfigTest {

    @Test
    void shouldConfigureSourceAndDltWithSamePartitionCount() {
        KafkaTopicProperties topicProperties = new KafkaTopicProperties();
        topicProperties.setSourceTopic("order-events");
        topicProperties.setDltTopic("order-events.DLT");
        topicProperties.setPartitions(3);
        topicProperties.setReplicas(2);

        KafkaTopicConfig config = new KafkaTopicConfig();

        NewTopic sourceTopic = config.orderEventsTopic(topicProperties);
        NewTopic dltTopic = config.orderEventsDltTopic(topicProperties);

        assertTopic(sourceTopic, "order-events");
        assertTopic(dltTopic, "order-events.DLT");
    }

    private void assertTopic(
            NewTopic topic,
            String expectedName) {

        assertThat(topic.name()).isEqualTo(expectedName);
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 2);
    }
}
