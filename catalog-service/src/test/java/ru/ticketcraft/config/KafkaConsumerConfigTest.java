package ru.ticketcraft.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

class KafkaConsumerConfigTest {

    @Test
    void shouldConfigureConcurrencyAndRecordAckMode() {

        @SuppressWarnings("unchecked")
        ConsumerFactory<String, Object> consumerFactory = mock(ConsumerFactory.class);

        DefaultErrorHandler errorHandler = mock(DefaultErrorHandler.class);

        KafkaConsumerProperties consumerProperties = new KafkaConsumerProperties(3);

        KafkaConsumerConfig config = new KafkaConsumerConfig();

        ConcurrentKafkaListenerContainerFactory<String, Object> factory = config
                .ticketReservationCommandKafkaListenerContainerFactory(consumerFactory, errorHandler,
                        consumerProperties);

        assertThat(factory.getConsumerFactory()).isSameAs(consumerFactory);

        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);

        ConcurrentMessageListenerContainer<String, Object> container = factory
                .createContainer("ticket-reservation-commands");

        assertThat(container.getConcurrency()).isEqualTo(3);
    }
}