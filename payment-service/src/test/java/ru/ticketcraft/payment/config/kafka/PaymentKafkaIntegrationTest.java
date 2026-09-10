package ru.ticketcraft.payment.config.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import ru.ticketcraft.payment.gateway.PaymentGateway;
import ru.ticketcraft.payment.gateway.PaymentResult;
import ru.ticketcraft.payment.model.Payment;
import ru.ticketcraft.payment.model.PaymentStatus;
import ru.ticketcraft.payment.repository.PaymentRepository;

@SpringBootTest(properties = { "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",

        "spring.kafka.consumer.group-id=payment-test-group", "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.enable-auto-commit=false",

        "spring.kafka.consumer.properties.spring.json.trusted.packages=ru.ticketcraft.dto",
        "spring.kafka.consumer.properties.spring.json.value.default.type=ru.ticketcraft.dto.PaymentRequestedEvent",

        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JacksonJsonSerializer",

        "spring.kafka.listener.ack-mode=manual", "spring.kafka.listener.auto-startup=true",

        "ticketcraft.kafka.request-topic=payment-requests", "ticketcraft.kafka.result-topic=payment-results",
        "ticketcraft.kafka.dlt-topic=payment-requests.DLT", "ticketcraft.kafka.partitions=3",
        "ticketcraft.kafka.replicas=1",

        "ticketcraft.kafka.retry.max-attempts=3", "ticketcraft.kafka.retry.back-off=100ms" })
@EmbeddedKafka(partitions = 3, topics = { PaymentKafkaIntegrationTest.REQUEST_TOPIC,
        PaymentKafkaIntegrationTest.RESULT_TOPIC, PaymentKafkaIntegrationTest.DLT_TOPIC })
@Testcontainers
@ActiveProfiles("test")
class PaymentKafkaIntegrationTest {

    static final String REQUEST_TOPIC = "payment-requests";
    static final String RESULT_TOPIC = "payment-results";
    static final String DLT_TOPIC = "payment-requests.DLT";

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

    @MockitoBean
    private PaymentGateway paymentGateway;

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

        try (Consumer<String, PaymentRequestedEvent> dltConsumer = createDltConsumer("payment-dlt-test-group-retry")) {

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

        try (Consumer<String, PaymentRequestedEvent> dltConsumer = createDltConsumer(
                "payment-dlt-test-group-success")) {

            waitForListenerAssignment();

            kafkaTemplate.send(REQUEST_TOPIC, event.messageId(), event).join();

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
    void shouldPublishSucceededEventFromOutboxToResultTopic() {
        PaymentRequestedEvent event = createEvent(1005L, "payment-message-outbox-success");

        when(paymentGateway.charge(any(UUID.class), eq(event.orderId()), eq(event.userId()), eq(event.amount())))
                .thenReturn(PaymentResult.success());

        try (Consumer<String, PaymentSucceededEvent> resultConsumer = createResultConsumer(
                "payment-result-test-group-success", PaymentSucceededEvent.class)) {

            waitForListenerAssignment();

            kafkaTemplate.send(REQUEST_TOPIC, event.messageId(), event).join();

            ConsumerRecord<String, PaymentSucceededEvent> resultRecord = KafkaTestUtils.getSingleRecord(resultConsumer,
                    RESULT_TOPIC, Duration.ofSeconds(10));

            assertThat(resultRecord).isNotNull();

            PaymentSucceededEvent resultEvent = resultRecord.value();

            assertThat(resultEvent).isNotNull();

            assertThat(resultEvent.orderId()).isEqualTo(event.orderId());

            assertThat(resultEvent.amount()).isEqualByComparingTo(event.amount());

            assertThat(resultEvent.paymentId()).isNotNull();

            assertThat(resultEvent.messageId()).isEqualTo("payment:" + resultEvent.paymentId() + ":succeeded");

            assertThat(resultRecord.key()).isEqualTo(event.orderId().toString());

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Optional<Payment> payment = paymentRepository.findByOrderId(event.orderId());

                assertThat(payment).isPresent();

                assertThat(payment.get().getId()).isEqualTo(resultEvent.paymentId());

                assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            });

            verify(paymentGateway, times(1)).charge(any(UUID.class), eq(event.orderId()), eq(event.userId()),
                    eq(event.amount()));
        }
    }

    @Test
    void shouldPublishFailedEventFromOutboxToResultTopic() {
        PaymentRequestedEvent event = createEvent(1006L, "payment-message-outbox-failed");

        when(paymentGateway.charge(any(UUID.class), eq(event.orderId()), eq(event.userId()), eq(event.amount())))
                .thenReturn(PaymentResult.failure("Insufficient funds"));

        try (Consumer<String, PaymentFailedEvent> resultConsumer = createResultConsumer(
                "payment-result-test-group-failed", PaymentFailedEvent.class)) {

            waitForListenerAssignment();

            kafkaTemplate.send(REQUEST_TOPIC, event.messageId(), event).join();

            ConsumerRecord<String, PaymentFailedEvent> resultRecord = KafkaTestUtils.getSingleRecord(resultConsumer,
                    RESULT_TOPIC, Duration.ofSeconds(10));

            PaymentFailedEvent resultEvent = resultRecord.value();

            assertThat(resultEvent).isNotNull();

            assertThat(resultEvent.orderId()).isEqualTo(event.orderId());

            assertThat(resultEvent.paymentId()).isNotNull();

            assertThat(resultEvent.amount()).isEqualByComparingTo(event.amount());

            assertThat(resultEvent.messageId()).isEqualTo("payment:" + resultEvent.paymentId() + ":failed");

            assertThat(resultEvent.reason()).isEqualTo("Insufficient funds");

            assertThat(resultRecord.key()).isEqualTo(event.orderId().toString());

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Optional<Payment> payment = paymentRepository.findByOrderId(event.orderId());

                assertThat(payment).isPresent();

                assertThat(payment.get().getId()).isEqualTo(resultEvent.paymentId());

                assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.FAILED);
            });

            verify(paymentGateway, times(1)).charge(any(UUID.class), eq(event.orderId()), eq(event.userId()),
                    eq(event.amount()));
        }
    }

    private <T> Consumer<String, T> createResultConsumer(String groupId, Class<T> eventType) {
        Map<String, Object> consumerProperties = KafkaTestUtils.consumerProps(embeddedKafkaBroker, groupId, false);

        JacksonJsonDeserializer<T> valueDeserializer = new JacksonJsonDeserializer<>(eventType);

        valueDeserializer.addTrustedPackages("ru.ticketcraft.dto");

        ConsumerFactory<String, T> consumerFactory = new DefaultKafkaConsumerFactory<>(consumerProperties,
                new StringDeserializer(), valueDeserializer);

        Consumer<String, T> consumer = consumerFactory.createConsumer();

        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, true, RESULT_TOPIC);

        return consumer;
    }

    private void waitForListenerAssignment() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer("paymentConsumer");

        assertThat(container).isNotNull();

        ContainerTestUtils.waitForAssignment(container, 3);
    }

    private Consumer<String, PaymentRequestedEvent> createDltConsumer(String groupId) {
        Map<String, Object> consumerProperties = KafkaTestUtils.consumerProps(embeddedKafkaBroker, groupId, false);

        JacksonJsonDeserializer<PaymentRequestedEvent> valueDeserializer = new JacksonJsonDeserializer<>(
                PaymentRequestedEvent.class);

        valueDeserializer.addTrustedPackages("ru.ticketcraft.dto");

        ConsumerFactory<String, PaymentRequestedEvent> consumerFactory = new DefaultKafkaConsumerFactory<>(
                consumerProperties, new StringDeserializer(), valueDeserializer);

        Consumer<String, PaymentRequestedEvent> consumer = consumerFactory.createConsumer();

        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, true, DLT_TOPIC);

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