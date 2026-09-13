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
    void shouldConfigureBothResultConsumersWithSameConcurrency() {

        @SuppressWarnings("unchecked")
        ConsumerFactory<String, Object> consumerFactory = mock(ConsumerFactory.class);

        DefaultErrorHandler errorHandler = mock(DefaultErrorHandler.class);

        KafkaConsumerProperties consumerProperties = new KafkaConsumerProperties(3, "order-ticket-reservation-results",
                "order-payment-results");

        KafkaConsumerConfig config = new KafkaConsumerConfig();

        ConcurrentKafkaListenerContainerFactory<String, Object> reservationFactory = config
                .ticketReservationResultKafkaListenerContainerFactory(consumerFactory, errorHandler,
                        consumerProperties);

        ConcurrentKafkaListenerContainerFactory<String, Object> paymentFactory = config
                .paymentResultKafkaListenerContainerFactory(consumerFactory, errorHandler, consumerProperties);

        ConcurrentMessageListenerContainer<String, Object> reservationContainer = reservationFactory
                .createContainer("ticket-reservation-results");

        ConcurrentMessageListenerContainer<String, Object> paymentContainer = paymentFactory
                .createContainer("payment-results");

        assertThat(reservationContainer.getConcurrency()).isEqualTo(3);

        assertThat(paymentContainer.getConcurrency()).isEqualTo(3);

        assertThat(reservationFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);

        assertThat(paymentFactory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
    }
}