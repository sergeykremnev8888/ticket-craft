package ru.ticketcraft.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import tools.jackson.databind.json.JsonMapper;

@Configuration
public class KafkaProducerConfig {

    @Bean
    JsonMapper objectMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    ProducerFactory<String, Object> producerFactory(KafkaProperties kafkaProperties) {

        Map<String, Object> properties = kafkaProperties.buildProducerProperties();

        Map<Class<?>, Serializer<?>> serializers = new LinkedHashMap<>();

        serializers.put(byte[].class, new ByteArraySerializer());

        serializers.put(Object.class, new JacksonJsonSerializer<>());

        DelegatingByTypeSerializer valueSerializer = new DelegatingByTypeSerializer(serializers, true);

        return new DefaultKafkaProducerFactory<>(properties, new StringSerializer(), valueSerializer);
    }

    @Bean
    KafkaTemplate<String, Object> kafkaTemplate(@Qualifier("producerFactory") ProducerFactory<String, Object> producerFactory) {

        return new KafkaTemplate<>(producerFactory);
    }
}