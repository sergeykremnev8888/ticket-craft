package ru.ticketcraft.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    private static final long RETRY_BACKOFF_MS = 1_000L;

    private static final long RETRY_ATTEMPTS = 2L;

    @Bean
    ConsumerFactory<String, Object> ticketReservationCommandConsumerFactory(KafkaProperties kafkaProperties) {

        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildConsumerProperties());

        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);

        properties.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class);

        properties.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "ru.ticketcraft.dto");

        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    ProducerFactory<String, Object> ticketReservationCommandDltProducerFactory(KafkaProperties kafkaProperties) {

        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());

        Map<Class<?>, Serializer<?>> serializers = new LinkedHashMap<>();

        serializers.put(byte[].class, new ByteArraySerializer());

        serializers.put(Object.class, new JacksonJsonSerializer<>());

        DelegatingByTypeSerializer valueSerializer = new DelegatingByTypeSerializer(serializers, true);

        return new DefaultKafkaProducerFactory<>(properties, new StringSerializer(), valueSerializer);
    }

    @Bean
    KafkaTemplate<String, Object> ticketReservationCommandDltKafkaTemplate(
            @Qualifier("ticketReservationCommandDltProducerFactory") ProducerFactory<String, Object> producerFactory) {

        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    DeadLetterPublishingRecoverer ticketReservationCommandDeadLetterPublishingRecoverer(
            @Qualifier("ticketReservationCommandDltKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
            KafkaTopicsProperties topics) {

        return new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(topics.ticketReservationCommandsDlt(), record.partition()));
    }

    @Bean
    DefaultErrorHandler ticketReservationCommandErrorHandler(
            @Qualifier("ticketReservationCommandDeadLetterPublishingRecoverer") DeadLetterPublishingRecoverer recoverer) {

        FixedBackOff backOff = new FixedBackOff(RETRY_BACKOFF_MS, RETRY_ATTEMPTS);

        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> ticketReservationCommandKafkaListenerContainerFactory(
            @Qualifier("ticketReservationCommandConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier("ticketReservationCommandErrorHandler") DefaultErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);

        factory.setCommonErrorHandler(errorHandler);

        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        return factory;
    }
}