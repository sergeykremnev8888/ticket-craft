package ru.ticketcraft.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PaymentKafkaPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfiguration.class)
                    .withPropertyValues(
                            "ticketcraft.kafka.request-topic=payment-requests",
                            "ticketcraft.kafka.command-topic=payment-commands",
                            "ticketcraft.kafka.result-topic=payment-results",
                            "ticketcraft.kafka.dlt-topic=payment-requests.DLT",
                            "ticketcraft.kafka.command-dlt-topic=payment-commands.DLT",
                            "ticketcraft.kafka.poll-timeout=1s");

    @Test
    void shouldBindScalingProperties() {
        contextRunner
                .withPropertyValues(
                        "ticketcraft.kafka.concurrency=3",
                        "ticketcraft.kafka.partitions=3",
                        "ticketcraft.kafka.replicas=2")
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed();

                    PaymentKafkaProperties properties =
                            context.getBean(
                                    PaymentKafkaProperties.class);

                    assertThat(properties.getRequestTopic())
                            .isEqualTo("payment-requests");

                    assertThat(properties.getCommandTopic())
                            .isEqualTo("payment-commands");

                    assertThat(properties.getResultTopic())
                            .isEqualTo("payment-results");

                    assertThat(properties.getDltTopic())
                            .isEqualTo("payment-requests.DLT");

                    assertThat(properties.getCommandDltTopic())
                            .isEqualTo("payment-commands.DLT");

                    assertThat(properties.getConcurrency())
                            .isEqualTo(3);

                    assertThat(properties.getPartitions())
                            .isEqualTo(3);

                    assertThat(properties.getReplicas())
                            .isEqualTo(2);

                    assertThat(properties.getPollTimeout())
                            .isNotNull();
                });
    }

    @Test
    void shouldRejectZeroConcurrency() {
        contextRunner
                .withPropertyValues(
                        "ticketcraft.kafka.concurrency=0")
                .run(context -> {
                    assertThat(context)
                            .hasFailed();

                    assertThat(context.getStartupFailure())
                            .isNotNull();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(
            PaymentKafkaProperties.class)
    static class TestConfiguration {
    }
}