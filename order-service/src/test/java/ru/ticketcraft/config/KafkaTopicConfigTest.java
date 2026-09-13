package ru.ticketcraft.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

class KafkaTopicConfigTest {

    @Test
    void shouldConfigureAllOrderKafkaTopicsWithSamePartitionCount() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "order-events",
                "ticket-reservation-commands",
                "ticket-reservation-results",
                "ticket-reservation-results.DLT",
                "payment-requests",
                "payment-results",
                "payment-results.DLT");

        KafkaTopicProperties topicProperties =
                new KafkaTopicProperties(3, 2);

        KafkaTopicConfig config = new KafkaTopicConfig();

        assertTopic(
                config.orderEventsTopic(topics, topicProperties),
                "order-events");

        assertTopic(
                config.ticketReservationCommandsTopic(
                        topics,
                        topicProperties),
                "ticket-reservation-commands");

        assertTopic(
                config.ticketReservationResultsTopic(
                        topics,
                        topicProperties),
                "ticket-reservation-results");

        assertTopic(
                config.ticketReservationResultsDltTopic(
                        topics,
                        topicProperties),
                "ticket-reservation-results.DLT");

        assertTopic(
                config.paymentRequestsTopic(topics, topicProperties),
                "payment-requests");

        assertTopic(
                config.paymentResultsTopic(topics, topicProperties),
                "payment-results");

        assertTopic(
                config.paymentResultsDltTopic(
                        topics,
                        topicProperties),
                "payment-results.DLT");
    }

    private void assertTopic(
            NewTopic topic,
            String expectedName) {

        assertThat(topic.name()).isEqualTo(expectedName);
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 2);
    }
}
