package ru.ticketcraft.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import ru.ticketcraft.dto.OrderEvent;

@ExtendWith(MockitoExtension.class)
class DeadLetterPublishingRecovererTest {

    @Mock
    private KafkaTemplate<String, OrderEvent> kafkaTemplate;

    @Captor
    private ArgumentCaptor<ProducerRecord<String, OrderEvent>> producerRecordCaptor;

    @Test
    void shouldPublishFailedRecordToConfiguredDltWithSamePartition() {
        KafkaTopicProperties properties = new KafkaTopicProperties();
        properties.setDltTopic("order-events.DLT");

        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, OrderEvent>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        KafkaConsumerConfig config = new KafkaConsumerConfig();

        DeadLetterPublishingRecoverer recoverer = config.deadLetterPublishingRecoverer(kafkaTemplate, properties);

        OrderEvent orderEvent = new OrderEvent(null, null, null, null, null, null, null, null);

        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("order-events", 2, 42L, "key", orderEvent);

        RuntimeException exception = new RuntimeException("Processing failed");

        recoverer.accept(record, exception);

        verify(kafkaTemplate).send(producerRecordCaptor.capture());

        ProducerRecord<String, OrderEvent> dltRecord = producerRecordCaptor.getValue();

        assertThat(dltRecord.topic()).isEqualTo("order-events.DLT");

        assertThat(dltRecord.partition()).isEqualTo(2);

        assertThat(dltRecord.key()).isEqualTo("key");

        assertThat(dltRecord.value()).isEqualTo(orderEvent);
    }
}