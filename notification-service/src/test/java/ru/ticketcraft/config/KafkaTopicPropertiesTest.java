package ru.ticketcraft.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class KafkaTopicPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldBindKafkaTopicProperties() {

        contextRunner.withPropertyValues("ticketcraft.kafka.topic.source-topic=order-events",
                "ticketcraft.kafka.topic.dlt-topic=order-events.DLT", "ticketcraft.kafka.topic.partitions=3",
                "ticketcraft.kafka.topic.replicas=3").run(context -> {

                    assertThat(context).hasNotFailed();

                    KafkaTopicProperties properties = context.getBean(KafkaTopicProperties.class);

                    assertThat(properties.getSourceTopic()).isEqualTo("order-events");

                    assertThat(properties.getDltTopic()).isEqualTo("order-events.DLT");

                    assertThat(properties.getPartitions()).isEqualTo(3);

                    assertThat(properties.getReplicas()).isEqualTo((short) 3);
                });
    }

    @Test
    void shouldRejectBlankSourceTopic() {

        contextRunner.withPropertyValues("ticketcraft.kafka.topic.source-topic=",
                "ticketcraft.kafka.topic.dlt-topic=order-events.DLT", "ticketcraft.kafka.topic.partitions=1",
                "ticketcraft.kafka.topic.replicas=1").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldRejectBlankDltTopic() {

        contextRunner.withPropertyValues("ticketcraft.kafka.topic.source-topic=order-events",
                "ticketcraft.kafka.topic.dlt-topic=", "ticketcraft.kafka.topic.partitions=1",
                "ticketcraft.kafka.topic.replicas=1").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldRejectZeroPartitions() {

        contextRunner.withPropertyValues("ticketcraft.kafka.topic.source-topic=order-events",
                "ticketcraft.kafka.topic.dlt-topic=order-events.DLT", "ticketcraft.kafka.topic.partitions=0",
                "ticketcraft.kafka.topic.replicas=1").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldRejectZeroReplicas() {

        contextRunner.withPropertyValues("ticketcraft.kafka.topic.source-topic=order-events",
                "ticketcraft.kafka.topic.dlt-topic=order-events.DLT", "ticketcraft.kafka.topic.partitions=1",
                "ticketcraft.kafka.topic.replicas=0").run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(KafkaTopicProperties.class)
    static class TestConfiguration {
    }
}