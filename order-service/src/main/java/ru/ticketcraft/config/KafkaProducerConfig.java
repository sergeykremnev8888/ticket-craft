package ru.ticketcraft.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import ru.ticketcraft.dto.OrderEvent;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    JsonMapper objectMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    ProducerFactory<String, OrderEvent> producerFactory(JsonMapper objectMapper) {
        Map<String, Object> configProps = new HashMap<>();

        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        JacksonJsonSerializer<OrderEvent> valueSerializer = new JacksonJsonSerializer<OrderEvent>(objectMapper);

        return new DefaultKafkaProducerFactory<>(configProps, new StringSerializer(), valueSerializer);
    }

    @Bean
    KafkaTemplate<String, OrderEvent> kafkaTemplate(ProducerFactory<String, OrderEvent> producerFactory) {

        return new KafkaTemplate<>(producerFactory);
    }
}