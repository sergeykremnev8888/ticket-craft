package ru.ticketcraft.payment.config.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.payment.config.PaymentKafkaProperties;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",

        "spring.kafka.consumer.group-id=payment-test-group",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.enable-auto-commit=false",

        "spring.kafka.producer.key-serializer="
                + "org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer="
                + "org.springframework.kafka.support.serializer.JacksonJsonSerializer",

        "spring.kafka.listener.auto-startup=false",

        "ticketcraft.kafka.request-topic=payment-requests",
        "ticketcraft.kafka.result-topic=payment-results",
        "ticketcraft.kafka.dlt-topic=payment-requests.DLT",

        "ticketcraft.kafka.partitions=3",
        "ticketcraft.kafka.replicas=1",

        "ticketcraft.kafka.retry.max-attempts=3",
        "ticketcraft.kafka.retry.back-off=100ms"
})
@EmbeddedKafka(
        partitions = 3,
        topics = {
                PaymentKafkaTopicConfigTest.REQUEST_TOPIC,
                PaymentKafkaTopicConfigTest.RESULT_TOPIC,
                PaymentKafkaTopicConfigTest.DLT_TOPIC
        }
)
@Testcontainers
@ActiveProfiles("test")
class PaymentKafkaTopicConfigTest {

    static final String REQUEST_TOPIC = "payment-requests";
    static final String RESULT_TOPIC = "payment-results";
    static final String DLT_TOPIC = "payment-requests.DLT";

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("payment_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private PaymentKafkaProperties kafkaProperties;

    @Test
    void shouldLoadKafkaTopicProperties() {
        assertThat(kafkaProperties.getRequestTopic()).isEqualTo(REQUEST_TOPIC);

        assertThat(kafkaProperties.getResultTopic()).isEqualTo(RESULT_TOPIC);

        assertThat(kafkaProperties.getDltTopic()).isEqualTo(DLT_TOPIC);

        assertThat(kafkaProperties.getPartitions()).isEqualTo(3);

        assertThat(kafkaProperties.getReplicas()).isEqualTo(1);
    }

    @Test
    void shouldHavePaymentTopics() throws Exception {
        Map<String, Object> adminProperties = new HashMap<>();

        adminProperties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString());

        try (AdminClient adminClient = AdminClient.create(adminProperties)) {

            Set<String> topics = adminClient.listTopics().names().get();

            assertThat(topics).contains(REQUEST_TOPIC, RESULT_TOPIC, DLT_TOPIC);
        }
    }

    @Test
    void shouldHaveThreePartitionsForPaymentTopics() throws Exception {
        Map<String, Object> adminProperties = new HashMap<>();

        adminProperties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString());

        try (AdminClient adminClient = AdminClient.create(adminProperties)) {

            Map<String, TopicDescription> topics = adminClient
                    .describeTopics(Set.of(REQUEST_TOPIC, RESULT_TOPIC, DLT_TOPIC)).allTopicNames().get();

            assertThat(topics.get(REQUEST_TOPIC).partitions()).hasSize(3);

            assertThat(topics.get(RESULT_TOPIC).partitions()).hasSize(3);

            assertThat(topics.get(DLT_TOPIC).partitions()).hasSize(3);
        }
    }
}