package ru.ticketcraft.payment.config.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.common.dto.PaymentFailedEvent;
import ru.ticketcraft.common.dto.PaymentRequestedEvent;
import ru.ticketcraft.common.dto.PaymentSucceededEvent;
import ru.ticketcraft.common.dto.RefundPaymentCommand;
import ru.ticketcraft.common.dto.TicketConfirmationFailureReason;
import ru.ticketcraft.payment.gateway.PaymentGateway;
import ru.ticketcraft.payment.gateway.PaymentResult;
import ru.ticketcraft.payment.model.Payment;
import ru.ticketcraft.payment.model.PaymentStatus;
import ru.ticketcraft.payment.repository.PaymentRepository;
import ru.ticketcraft.payment.service.PaymentCommandValidator;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = { "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",

        "spring.kafka.consumer.group-id=payment-test-group", "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.enable-auto-commit=false",

        "spring.kafka.consumer.properties.spring.json.trusted.packages=ru.ticketcraft.common.dto",
        "spring.kafka.consumer.properties.spring.json.value.default.type=ru.ticketcraft.common.dto.PaymentRequestedEvent",

        "spring.kafka.listener.ack-mode=manual", "spring.kafka.listener.auto-startup=true",

        "ticketcraft.kafka.request-topic=payment-requests", "ticketcraft.kafka.result-topic=payment-results",
        "ticketcraft.kafka.dlt-topic=payment-requests.DLT", "ticketcraft.kafka.concurrency=3",
        "ticketcraft.kafka.partitions=3", "ticketcraft.kafka.replicas=1",

        "ticketcraft.kafka.command-topic=payment-commands",
        "ticketcraft.kafka.command-dlt-topic=payment-commands.DLT",

        "ticketcraft.kafka.retry.max-attempts=3", "ticketcraft.kafka.retry.back-off=100ms" })
@EmbeddedKafka(partitions = 3,
               topics = {
                       PaymentKafkaIntegrationTest.REQUEST_TOPIC,
                       PaymentKafkaIntegrationTest.RESULT_TOPIC,
                       PaymentKafkaIntegrationTest.DLT_TOPIC,
                       PaymentKafkaIntegrationTest.PARTITIONING_TOPIC,
                       PaymentKafkaIntegrationTest.COMMAND_TOPIC,
                       PaymentKafkaIntegrationTest.COMMAND_DLT_TOPIC
               }
       )
@Testcontainers
@ActiveProfiles("test")
class PaymentKafkaIntegrationTest {

    static final String REQUEST_TOPIC = "payment-requests";
    static final String RESULT_TOPIC = "payment-results";
    static final String DLT_TOPIC = "payment-requests.DLT";
    static final String PARTITIONING_TOPIC = "payment-partitioning-test";
    static final String COMMAND_TOPIC = "payment-commands";
    static final String COMMAND_DLT_TOPIC = "payment-commands.DLT";

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("payment_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PaymentGateway paymentGateway;

    @MockitoSpyBean
    private PaymentCommandValidator commandValidator;

    @Test
    void shouldStartThreeConcurrentPaymentConsumers() {
        MessageListenerContainer container =
                listenerRegistry.getListenerContainer("paymentConsumer");

        assertThat(container)
                .isInstanceOf(ConcurrentMessageListenerContainer.class);

        @SuppressWarnings("unchecked")
        ConcurrentMessageListenerContainer<String, PaymentRequestedEvent> concurrentContainer =
                (ConcurrentMessageListenerContainer<String, PaymentRequestedEvent>) container;

        assertThat(concurrentContainer.getContainers()).hasSize(3);
    }

    @Test
    void shouldMapSameKafkaKeyToSamePartitionInOrder() {
        String key = "order-partition-key-" + UUID.randomUUID();

        PaymentRequestedEvent firstEvent =
                createEvent(2001L, "partition-message-1");

        PaymentRequestedEvent secondEvent =
                createEvent(2002L, "partition-message-2");

        SendResult<String, Object> firstResult =
                kafkaTemplate.send(PARTITIONING_TOPIC, key, firstEvent).join();

        SendResult<String, Object> secondResult =
                kafkaTemplate.send(PARTITIONING_TOPIC, key, secondEvent).join();

        assertThat(secondResult.getRecordMetadata().partition())
                .isEqualTo(firstResult.getRecordMetadata().partition());

        assertThat(secondResult.getRecordMetadata().offset())
                .isGreaterThan(firstResult.getRecordMetadata().offset());
    }

    @Test
    void shouldProcessPaymentSuccessfully() {
        PaymentRequestedEvent event = createEvent(1001L, "payment-message-success");

        when(paymentGateway.charge(any(UUID.class), eq(event.orderId()), eq(event.userId()), eq(event.amount())))
                .thenReturn(PaymentResult.success());

        waitForListenerAssignment();

        kafkaTemplate.send(REQUEST_TOPIC, event.messageId(), event).join();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Optional<Payment> payment = paymentRepository.findByOrderId(event.orderId());

            assertThat(payment).isPresent();
            assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        });

        verify(paymentGateway, times(1)).charge(any(UUID.class), eq(event.orderId()), eq(event.userId()),
                eq(event.amount()));
    }

    @Test
    void shouldRetryThreeTimesAndPublishToDlt() {
        PaymentRequestedEvent event = createEvent(1002L, "payment-message-retry");

        doThrow(new RuntimeException("Payment gateway unavailable")).when(paymentGateway).charge(any(UUID.class),
                eq(event.orderId()), eq(event.userId()), eq(event.amount()));

        ArgumentCaptor<UUID> paymentIdCaptor = ArgumentCaptor.forClass(UUID.class);

        try (Consumer<String, PaymentRequestedEvent> dltConsumer = createDltConsumer("payment-dlt-test-group-retry",
                2)) {

            waitForListenerAssignment();

            ProducerRecord<String, Object> record = new ProducerRecord<>(REQUEST_TOPIC, 2, event.messageId(), event);

            kafkaTemplate.send(record).join();

            verify(paymentGateway, timeout(10_000).times(3)).charge(paymentIdCaptor.capture(), eq(event.orderId()),
                    eq(event.userId()), eq(event.amount()));

            List<UUID> paymentIds = paymentIdCaptor.getAllValues();

            assertThat(paymentIds).hasSize(3).allMatch(id -> id != null);

            assertThat(paymentIds.get(0)).isEqualTo(paymentIds.get(1));

            assertThat(paymentIds.get(1)).isEqualTo(paymentIds.get(2));

            ConsumerRecord<String, PaymentRequestedEvent> dltRecord = KafkaTestUtils.getSingleRecord(dltConsumer,
                    DLT_TOPIC, Duration.ofSeconds(10));

            assertThat(dltRecord.value()).isNotNull();
            assertThat(dltRecord.value().messageId()).isEqualTo(event.messageId());
            assertThat(dltRecord.value().orderId()).isEqualTo(event.orderId());
            assertThat(dltRecord.key()).isEqualTo(event.messageId());
            assertThat(dltRecord.partition()).isEqualTo(2);

            Optional<Payment> payment = paymentRepository.findByOrderId(event.orderId());

            assertThat(payment).isPresent();
            assertThat(payment.get().getId()).isEqualTo(paymentIds.get(0));
            assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.PENDING);
        }
    }

    @Test
    void shouldNotRetryWhenPaymentSucceeds() {
        PaymentRequestedEvent event = createEvent(1003L, "payment-message-no-retry");

        when(paymentGateway.charge(any(UUID.class), eq(event.orderId()), eq(event.userId()), eq(event.amount())))
                .thenReturn(PaymentResult.success());

        try (Consumer<String, PaymentRequestedEvent> dltConsumer = createDltConsumer("payment-dlt-test-group-success",
                0)) {

            waitForListenerAssignment();

            ProducerRecord<String, Object> record = new ProducerRecord<>(REQUEST_TOPIC, 0, event.messageId(), event);

            kafkaTemplate.send(record).join();

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Optional<Payment> payment = paymentRepository.findByOrderId(event.orderId());

                assertThat(payment).isPresent();
                assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            });

            verify(paymentGateway, times(1)).charge(any(UUID.class), eq(event.orderId()), eq(event.userId()),
                    eq(event.amount()));

            ConsumerRecord<String, PaymentRequestedEvent> unexpectedDltRecord = pollSingleRecordIfPresent(dltConsumer,
                    DLT_TOPIC, Duration.ofSeconds(2));

            assertThat(unexpectedDltRecord).isNull();
        }
    }

    @Test
    void shouldReusePaymentIdWhenRetryingPendingPayment() {
        PaymentRequestedEvent event = createEvent(1004L, "payment-message-pending-retry");

        when(paymentGateway.charge(any(UUID.class), eq(event.orderId()), eq(event.userId()), eq(event.amount())))
                .thenThrow(new RuntimeException("Temporary gateway failure")).thenReturn(PaymentResult.success());

        ArgumentCaptor<UUID> paymentIdCaptor = ArgumentCaptor.forClass(UUID.class);

        waitForListenerAssignment();

        kafkaTemplate.send(REQUEST_TOPIC, event.messageId(), event).join();

        verify(paymentGateway, timeout(10_000).times(2)).charge(paymentIdCaptor.capture(), eq(event.orderId()),
                eq(event.userId()), eq(event.amount()));

        List<UUID> paymentIds = paymentIdCaptor.getAllValues();

        assertThat(paymentIds).hasSize(2).allMatch(id -> id != null);

        assertThat(paymentIds.get(0)).isEqualTo(paymentIds.get(1));

        UUID paymentId = paymentIds.get(0);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Optional<Payment> payment = paymentRepository.findByOrderId(event.orderId());

            assertThat(payment).isPresent();
            assertThat(payment.get().getId()).isEqualTo(paymentId);
            assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        });
    }

    @Test
    void shouldSaveSucceededEventToOutbox() {
        PaymentRequestedEvent event = createEvent(1005L, "payment-message-outbox-success");

        when(paymentGateway.charge(any(UUID.class),
                                   eq(event.orderId()),
                                   eq(event.userId()),
                                   eq(event.amount()))).thenReturn(PaymentResult.success());

        waitForListenerAssignment();

        kafkaTemplate.send(REQUEST_TOPIC, event.messageId(), event).join();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Payment payment = paymentRepository.findByOrderId(event.orderId()).orElseThrow();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT message_id, event_type, payload::text AS payload
                    FROM payment_outbox
                    WHERE order_id = ?
                    """, event.orderId());

            PaymentSucceededEvent resultEvent = JsonMapper.builder()
                                                          .build()
                                                          .readValue((String) row.get("payload"),
                                                                     PaymentSucceededEvent.class);

            String expectedMessageId = "payment:" + payment.getId() + ":succeeded";

            assertThat(row.get("event_type")).isEqualTo("PaymentSucceededEvent");
            assertThat(row.get("message_id")).isEqualTo(expectedMessageId);

            assertThat(resultEvent.messageId()).isEqualTo(expectedMessageId);
            assertThat(resultEvent.orderId()).isEqualTo(event.orderId());
            assertThat(resultEvent.paymentId()).isEqualTo(payment.getId());
            assertThat(resultEvent.amount()).isEqualByComparingTo(event.amount());
        });

        verify(paymentGateway, times(1)).charge(any(UUID.class),
                                                eq(event.orderId()),
                                                eq(event.userId()),
                                                eq(event.amount()));
    }

    @Test
    void shouldSaveFailedEventToOutbox() {
        PaymentRequestedEvent event = createEvent(1006L, "payment-message-outbox-failed");

        when(paymentGateway.charge(any(UUID.class),
                                   eq(event.orderId()),
                                   eq(event.userId()),
                                   eq(event.amount()))).thenReturn(PaymentResult.failure("Insufficient funds"));

        waitForListenerAssignment();

        kafkaTemplate.send(REQUEST_TOPIC, event.messageId(), event).join();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Payment payment = paymentRepository.findByOrderId(event.orderId()).orElseThrow();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT message_id, event_type, payload::text AS payload
                    FROM payment_outbox
                    WHERE order_id = ?
                    """, event.orderId());

            PaymentFailedEvent resultEvent = JsonMapper.builder()
                                                       .build()
                                                       .readValue((String) row.get("payload"),
                                                                  PaymentFailedEvent.class);

            String expectedMessageId = "payment:" + payment.getId() + ":failed";

            assertThat(row.get("event_type")).isEqualTo("PaymentFailedEvent");
            assertThat(row.get("message_id")).isEqualTo(expectedMessageId);

            assertThat(resultEvent.messageId()).isEqualTo(expectedMessageId);
            assertThat(resultEvent.orderId()).isEqualTo(event.orderId());
            assertThat(resultEvent.paymentId()).isEqualTo(payment.getId());
            assertThat(resultEvent.amount()).isEqualByComparingTo(event.amount());
            assertThat(resultEvent.reason()).isEqualTo("Insufficient funds");
        });

        verify(paymentGateway, times(1)).charge(any(UUID.class),
                                                eq(event.orderId()),
                                                eq(event.userId()),
                                                eq(event.amount()));
    }

    @Test
    void shouldPublishMalformedMessageToDltWithoutInvokingGateway() {
        String messageKey = "malformed-payment-message";

        byte[] malformedJson = """
                {
                  "messageId": "broken-message",
                  "orderId":
                }
                """.getBytes(StandardCharsets.UTF_8);

        try (Consumer<String, byte[]> dltConsumer = createRawDltConsumer("payment-dlt-test-group-malformed", 1)) {

            waitForListenerAssignment();

            sendRawMessage(REQUEST_TOPIC, messageKey, malformedJson, 1);

            ConsumerRecord<String, byte[]> dltRecord = KafkaTestUtils.getSingleRecord(dltConsumer, DLT_TOPIC,
                    Duration.ofSeconds(10));

            assertThat(dltRecord.key()).isEqualTo(messageKey);
            assertThat(dltRecord.partition()).isEqualTo(1);
            assertThat(dltRecord.value()).isEqualTo(malformedJson);

            verifyNoInteractions(paymentGateway);
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "0.00", "-1.00", "100.001", "100000000000000000.00" })
    void shouldSendInvalidPaymentToDltWithoutRetry(String amountValue) {
        int partition = 1;
        Long orderId = 91001L;

        BigDecimal amount = amountValue == null ? null : new BigDecimal(amountValue);

        PaymentRequestedEvent event = new PaymentRequestedEvent("invalid-payment-" + UUID.randomUUID(), orderId, 200L,
                amount, Instant.parse("2026-01-01T10:00:00Z"));

        try (Consumer<String, byte[]> dltConsumer = createRawDltConsumer("invalid-payment-dlt-" + UUID.randomUUID(),
                                                                         DLT_TOPIC,
                                                                         partition)) {

            waitForListenerAssignment();

            kafkaTemplate.send(new ProducerRecord<String, Object>(REQUEST_TOPIC, partition, event.messageId(), event))
                         .join();

            ConsumerRecord<String, byte[]> dltRecord = awaitRecordByKey(dltConsumer,
                                                                        event.messageId(),
                                                                        Duration.ofSeconds(10));

            assertThat(dltRecord.partition()).isEqualTo(partition);

            PaymentRequestedEvent restored = JsonMapper.builder()
                                                       .build()
                                                       .readValue(dltRecord.value(), PaymentRequestedEvent.class);

            assertThat(restored).isEqualTo(event);

            verify(commandValidator, times(1)).validate(eq(event));

            verifyNoInteractions(paymentGateway);

            assertThat(paymentRepository.findByOrderId(orderId)).isEmpty();
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "0.00", "-1.00", "100.001", "100000000000000000.00" })
    void shouldSendInvalidRefundToDltWithoutRetry(String amountValue) {
        int partition = 1;

        BigDecimal amount = amountValue == null ? null : new BigDecimal(amountValue);

        RefundPaymentCommand command = new RefundPaymentCommand("invalid-refund-" + UUID.randomUUID(), 91002L,
                UUID.randomUUID(), amount, TicketConfirmationFailureReason.RESERVATION_EXPIRED,
                Instant.parse("2026-01-01T10:00:00Z"));

        try (Consumer<String, byte[]> dltConsumer = createRawDltConsumer("invalid-refund-dlt-" + UUID.randomUUID(),
                                                                         COMMAND_DLT_TOPIC,
                                                                         partition)) {

            MessageListenerContainer container = listenerRegistry.getListenerContainer("paymentRefundConsumer");

            assertThat(container).isNotNull();
            ContainerTestUtils.waitForAssignment(container, 3);

            kafkaTemplate.send(new ProducerRecord<String, Object>(COMMAND_TOPIC, partition, command.messageId(),
                    command)).join();

            ConsumerRecord<String, byte[]> dltRecord = awaitRecordByKey(dltConsumer,
                                                                        command.messageId(),
                                                                        Duration.ofSeconds(10));

            assertThat(dltRecord.partition()).isEqualTo(partition);

            RefundPaymentCommand restored = JsonMapper.builder()
                                                      .build()
                                                      .readValue(dltRecord.value(), RefundPaymentCommand.class);

            assertThat(restored).isEqualTo(command);

            verify(commandValidator, times(1)).validate(eq(command));

            verifyNoInteractions(paymentGateway);
        }
    }

    private <T> ConsumerRecord<String, T> awaitRecordByKey(Consumer<String, T> consumer, String expectedKey,
            Duration timeout) {

        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, T> records = consumer.poll(Duration.ofMillis(250));

            for (ConsumerRecord<String, T> record : records) {
                if (expectedKey.equals(record.key())) {
                    return record;
                }
            }
        }

        throw new AssertionError("No Kafka record found with key: " + expectedKey);
    }

    private void sendRawMessage(String topic, String key, byte[] value, int partition) {

        Map<String, Object> producerProperties = KafkaTestUtils.producerProps(embeddedKafkaBroker);

        ProducerFactory<String, byte[]> producerFactory = new DefaultKafkaProducerFactory<>(producerProperties,
                new StringSerializer(), new ByteArraySerializer());

        KafkaTemplate<String, byte[]> rawKafkaTemplate = new KafkaTemplate<>(producerFactory);

        try {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, partition, key, value);

            rawKafkaTemplate.send(record).join();
        } finally {
            rawKafkaTemplate.destroy();
        }
    }

    private Consumer<String, byte[]> createRawDltConsumer(String groupId, int partition) {
        return createRawDltConsumer(groupId, DLT_TOPIC, partition);
    }

    private Consumer<String, byte[]> createRawDltConsumer(String groupId, String topic, int partition) {
        Map<String, Object> properties = KafkaTestUtils.consumerProps(embeddedKafkaBroker, groupId, false);

        ConsumerFactory<String, byte[]> consumerFactory = new DefaultKafkaConsumerFactory<>(properties,
                new StringDeserializer(), new ByteArrayDeserializer());

        Consumer<String, byte[]> consumer = consumerFactory.createConsumer();

        TopicPartition topicPartition = new TopicPartition(topic, partition);

        consumer.assign(List.of(topicPartition));
        consumer.seekToEnd(List.of(topicPartition));

        // Завершаем определение offset до отправки тестового сообщения.
        consumer.position(topicPartition);

        return consumer;
    }

    private void waitForListenerAssignment() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer("paymentConsumer");

        assertThat(container).isNotNull();

        ContainerTestUtils.waitForAssignment(container, 3);
    }

    private Consumer<String, PaymentRequestedEvent> createDltConsumer(String groupId, int partition) {
        Map<String, Object> consumerProperties = KafkaTestUtils.consumerProps(embeddedKafkaBroker, groupId, false);

        JacksonJsonDeserializer<PaymentRequestedEvent> valueDeserializer = new JacksonJsonDeserializer<>(
                PaymentRequestedEvent.class);

        valueDeserializer.addTrustedPackages("ru.ticketcraft.dto");

        ConsumerFactory<String, PaymentRequestedEvent> consumerFactory = new DefaultKafkaConsumerFactory<>(
                consumerProperties, new StringDeserializer(), valueDeserializer);

        Consumer<String, PaymentRequestedEvent> consumer = consumerFactory.createConsumer();

        TopicPartition topicPartition = new TopicPartition(DLT_TOPIC, partition);

        consumer.assign(List.of(topicPartition));
        consumer.seekToEnd(List.of(topicPartition));
        consumer.position(topicPartition);

        return consumer;
    }

    private ConsumerRecord<String, PaymentRequestedEvent> pollSingleRecordIfPresent(
            Consumer<String, PaymentRequestedEvent> consumer, String topic, Duration timeout) {

        ConsumerRecords<String, PaymentRequestedEvent> records = consumer.poll(timeout);

        assertThat(records.count()).isLessThanOrEqualTo(1);

        if (records.isEmpty()) {
            return null;
        }

        return records.iterator().next();
    }

    private PaymentRequestedEvent createEvent(Long orderId, String messageId) {

        return new PaymentRequestedEvent(messageId, orderId, 200L, new BigDecimal("150.00"),
                Instant.parse("2026-09-09T10:00:00Z"));
    }
}