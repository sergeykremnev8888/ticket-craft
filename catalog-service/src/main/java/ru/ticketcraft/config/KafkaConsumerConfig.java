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

import ru.ticketcraft.dto.ReserveTicketCommand;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    ConsumerFactory<String, ReserveTicketCommand> reserveTicketConsumerFactory(KafkaProperties kafkaProperties) {

        Map<String, Object> properties = kafkaProperties.buildConsumerProperties();

        JacksonJsonDeserializer<ReserveTicketCommand> valueDeserializer = new JacksonJsonDeserializer<>(
                ReserveTicketCommand.class);

        valueDeserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), valueDeserializer);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, ReserveTicketCommand> reserveTicketKafkaListenerContainerFactory(
            ConsumerFactory<String, ReserveTicketCommand> reserveTicketConsumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, ReserveTicketCommand> factory = new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(reserveTicketConsumerFactory);

        return factory;
    }
}