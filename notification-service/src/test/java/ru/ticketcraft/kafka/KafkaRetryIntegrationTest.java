package ru.ticketcraft.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.service.IdempotentNotificationProcessor;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",

        "spring.kafka.consumer.group-id=notification-test-group",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.enable-auto-commit=false",

        "spring.kafka.consumer.properties.spring.json.trusted.packages=ru.ticketcraft.dto",
        "spring.kafka.consumer.properties.spring.json.value.default.type=ru.ticketcraft.dto.OrderEvent",

        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JacksonJsonSerializer",

        "spring.kafka.listener.ack-mode=manual",
        "spring.kafka.listener.auto-startup=true",

        "ticketcraft.kafka.topic.source-topic=order-events",
        "ticketcraft.kafka.topic.dlt-topic=order-events.DLT",
        "ticketcraft.kafka.topic.partitions=3",
        "ticketcraft.kafka.topic.replicas=1",

        "ticketcraft.kafka.retry.max-attempts=3",
        "ticketcraft.kafka.retry.backoff-ms=100"
})
@EmbeddedKafka(
        partitions = 3,
        topics = {
                KafkaRetryIntegrationTest.SOURCE_TOPIC,
                KafkaRetryIntegrationTest.DLT_TOPIC
        }
)
@Testcontainers
class KafkaRetryIntegrationTest {

    public static final String SOURCE_TOPIC = "order-events";
    public static final String DLT_TOPIC = "order-events.DLT";

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("notification_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private KafkaTemplate<String, OrderEvent> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @MockitoBean
    private IdempotentNotificationProcessor processor;

    @Test
    void shouldHaveDltTopic() throws Exception {
        Map<String, Object> adminProperties = new HashMap<>();
        adminProperties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString());

        try (AdminClient adminClient = AdminClient.create(adminProperties)) {
            Set<String> topics = adminClient.listTopics().names().get();
            assertThat(topics).contains(DLT_TOPIC);
        }
    }

    @Test
    void shouldRetryThreeTimesAndPublishToDlt() {

        OrderEvent event = createEvent();

        doThrow(new RuntimeException("Processing failed")).when(processor).process(any(OrderEvent.class));

        try (Consumer<String, OrderEvent> dltConsumer = createDltConsumer()) {

            waitForListenerAssignment();

            ProducerRecord<String, OrderEvent> record = new ProducerRecord<>(SOURCE_TOPIC, 2, event.getMessageId(),
                    event);

            kafkaTemplate.send(record).join();

            verify(processor, timeout(10_000).times(3)).process(any(OrderEvent.class));

            ConsumerRecord<String, OrderEvent> dltRecord = KafkaTestUtils.getSingleRecord(dltConsumer, DLT_TOPIC,
                    Duration.ofSeconds(10));

            assertThat(dltRecord.value()).isNotNull();

            assertThat(dltRecord.value().getMessageId()).isEqualTo(event.getMessageId());

            assertThat(dltRecord.key()).isEqualTo(event.getMessageId());

            assertThat(dltRecord.partition()).isEqualTo(2);
        }
    }

    @Test
    void shouldProcessSuccessfullyWithoutRetryAndDlt() {

        OrderEvent event = createEvent();

        try (Consumer<String, OrderEvent> dltConsumer = createDltConsumer()) {

            waitForListenerAssignment();

            kafkaTemplate.send(SOURCE_TOPIC, event.getMessageId(), event).join();

            ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);

            verify(processor, timeout(10_000).times(1)).process(eventCaptor.capture());

            OrderEvent processedEvent = eventCaptor.getValue();
            assertThat(processedEvent.getMessageId()).isEqualTo(event.getMessageId());
            assertThat(processedEvent.getOrderId()).isEqualTo(event.getOrderId());
            assertThat(processedEvent.getUserId()).isEqualTo(event.getUserId());
            assertThat(processedEvent.getEventId()).isEqualTo(event.getEventId());
            assertThat(processedEvent.getTicketIds()).containsExactlyElementsOf(event.getTicketIds());
            assertThat(processedEvent.getTotalPrice()).isEqualByComparingTo(event.getTotalPrice());
            assertThat(processedEvent.getState()).isEqualTo(event.getState());
            assertThat(processedEvent.getCreatedAt()).isEqualTo(event.getCreatedAt());

            ConsumerRecords<String, OrderEvent> records = dltConsumer.poll(Duration.ofSeconds(2));
            assertThat(records).isEmpty();
        }
    }

    private void waitForListenerAssignment() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer("notificationConsumer");

        assertThat(container).isNotNull();

        ContainerTestUtils.waitForAssignment(container, 3);
    }

    private Consumer<String, OrderEvent> createDltConsumer() {
        Map<String, Object> consumerProperties = 
                KafkaTestUtils.consumerProps(embeddedKafkaBroker, "dlt-test-group", false);
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        JacksonJsonDeserializer<OrderEvent> valueDeserializer = new JacksonJsonDeserializer<>(OrderEvent.class);
        valueDeserializer.addTrustedPackages("ru.ticketcraft.dto");

        ConsumerFactory<String, OrderEvent> consumerFactory = new DefaultKafkaConsumerFactory<>(consumerProperties,
                new StringDeserializer(), valueDeserializer);

        Consumer<String, OrderEvent> consumer = consumerFactory.createConsumer();

        consumer.subscribe(List.of(DLT_TOPIC));

        return consumer;
    }

    private OrderEvent createEvent() {
        UUID eventId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        UUID ticketId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        return new OrderEvent("11111111-1111-1111-1111-111111111111", 123L, 10L, eventId, List.of(ticketId),
                new BigDecimal("100.00"), OrderState.CREATED, Instant.parse("2026-01-01T10:00:00Z"));
    }
}
