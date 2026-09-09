package ru.ticketcraft.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class KafkaRetryPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldBindKafkaRetryProperties() {
        contextRunner
                .withPropertyValues("ticketcraft.kafka.retry.max-attempts=3", "ticketcraft.kafka.retry.backoff-ms=1000")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    KafkaRetryProperties properties = context.getBean(KafkaRetryProperties.class);
                    assertThat(properties.getMaxAttempts()).isEqualTo(3);
                    assertThat(properties.getBackoffMs()).isEqualTo(1000L);
                });
    }

    @Test
    void shouldRejectZeroMaxAttempts() {
        contextRunner
                .withPropertyValues("ticketcraft.kafka.retry.max-attempts=0", "ticketcraft.kafka.retry.backoff-ms=1000")
                .run(context -> {

                    assertThat(context).hasFailed();
                });
    }

    @Test
    void shouldRejectNegativeBackoff() {
        contextRunner
                .withPropertyValues("ticketcraft.kafka.retry.max-attempts=3", "ticketcraft.kafka.retry.backoff-ms=-1")
                .run(context -> {

                    assertThat(context).hasFailed();
                });
    }

    @Configuration
    @EnableConfigurationProperties(KafkaRetryProperties.class)
    static class TestConfiguration {
    }
}