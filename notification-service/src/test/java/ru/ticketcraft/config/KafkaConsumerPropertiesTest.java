package ru.ticketcraft.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class KafkaConsumerPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of())
                    .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldBindConcurrency() {
        contextRunner
                .withPropertyValues(
                        "ticketcraft.kafka.consumer.concurrency=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    KafkaConsumerProperties properties =
                            context.getBean(KafkaConsumerProperties.class);

                    assertThat(properties.concurrency()).isEqualTo(3);
                });
    }

    @Test
    void shouldRejectNonPositiveConcurrency() {
        contextRunner
                .withPropertyValues(
                        "ticketcraft.kafka.consumer.concurrency=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KafkaConsumerProperties.class)
    static class TestConfiguration {
    }
}
