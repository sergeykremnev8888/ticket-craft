package ru.ticketcraft.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

class KafkaTopicConfigTest {

    @Test
    void shouldConfigureSourceResultAndDltTopicsWithSamePartitionCount() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "ticket-reservation-commands",
                "ticket-reservation-commands.DLT",
                "ticket-reservation-results");

        KafkaTopicProperties topicProperties =
                new KafkaTopicProperties(3, 2);

        KafkaTopicConfig config = new KafkaTopicConfig();

        NewTopic sourceTopic = config.ticketReservationCommandsTopic(
                topics,
                topicProperties);

        NewTopic dltTopic = config.ticketReservationCommandsDltTopic(
                topics,
                topicProperties);

        NewTopic resultTopic = config.ticketReservationResultsTopic(
                topics,
                topicProperties);

        assertTopic(sourceTopic, "ticket-reservation-commands");
        assertTopic(dltTopic, "ticket-reservation-commands.DLT");
        assertTopic(resultTopic, "ticket-reservation-results");
    }

    private void assertTopic(
            NewTopic topic,
            String expectedName) {

        assertThat(topic.name()).isEqualTo(expectedName);
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 2);
    }
}
