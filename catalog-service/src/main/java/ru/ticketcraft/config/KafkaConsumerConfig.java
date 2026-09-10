package ru.ticketcraft.config;

import java.util.Map;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    ConsumerFactory<String, Object> ticketReservationCommandConsumerFactory(KafkaProperties kafkaProperties) {

        Map<String, Object> properties = kafkaProperties.buildConsumerProperties();

        JacksonJsonDeserializer<Object> valueDeserializer = new JacksonJsonDeserializer<>();

        valueDeserializer.addTrustedPackages("ru.ticketcraft.dto");

        return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), valueDeserializer);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> ticketReservationCommandKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> ticketReservationCommandConsumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(ticketReservationCommandConsumerFactory);

        return factory;
    }
}