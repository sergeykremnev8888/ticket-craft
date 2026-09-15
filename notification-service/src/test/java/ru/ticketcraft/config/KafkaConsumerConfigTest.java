package ru.ticketcraft.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

import ru.ticketcraft.dto.OrderEvent;

@ExtendWith(MockitoExtension.class)
class KafkaConsumerConfigTest {

    @Mock
    private ConsumerFactory<String, OrderEvent> consumerFactory;

    @Test
    void shouldConfigureListenerContainerFactory() {
        DefaultErrorHandler errorHandler = mock(DefaultErrorHandler.class);
        KafkaConsumerProperties consumerProperties = new KafkaConsumerProperties(3);

        KafkaConsumerConfig config = new KafkaConsumerConfig();
        KafkaProperties kafkaProperties = new KafkaProperties();

        ConcurrentKafkaListenerContainerFactory<String, OrderEvent> factory = config
                .kafkaListenerContainerFactory(consumerFactory, errorHandler, consumerProperties, kafkaProperties);

        assertThat(factory.getConsumerFactory()).isSameAs(consumerFactory);

        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);

        ConcurrentMessageListenerContainer<String, OrderEvent> container = factory.createContainer("order-events");

        assertThat(container.getConcurrency()).isEqualTo(3);
    }
}