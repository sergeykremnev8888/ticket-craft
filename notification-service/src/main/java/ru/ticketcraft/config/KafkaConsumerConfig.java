package ru.ticketcraft.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import ru.ticketcraft.dto.OrderEvent;

@Configuration
@EnableConfigurationProperties({ KafkaRetryProperties.class, KafkaConsumerProperties.class })
public class KafkaConsumerConfig {

    @Bean
    ProducerFactory<String, Object> notificationProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());

        Map<Class<?>, Serializer<?>> serializers = new LinkedHashMap<>();
        serializers.put(byte[].class, new ByteArraySerializer());
        serializers.put(Object.class, new JacksonJsonSerializer<>());

        DelegatingByTypeSerializer valueSerializer = new DelegatingByTypeSerializer(serializers, true);

        return new DefaultKafkaProducerFactory<>(properties, new StringSerializer(), valueSerializer);
    }

    @Bean
    KafkaTemplate<String, Object> notificationKafkaTemplate(@Qualifier("notificationProducerFactory")
                                                            ProducerFactory<String, Object> producerFactory,
                                                            KafkaProperties kafkaProperties) {
        KafkaTemplate<String, Object> kafkaTemplate = new KafkaTemplate<>(producerFactory);
        kafkaTemplate.setObservationEnabled(kafkaProperties.getTemplate().isObservationEnabled());
        return kafkaTemplate;
    }

    @Bean
    DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(@Qualifier("notificationKafkaTemplate")
                                                                KafkaTemplate<String, Object> kafkaTemplate,
                                                                KafkaTopicProperties topicProperties) {
        return new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(topicProperties.getDltTopic(), record.partition()));
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer,
                                          KafkaRetryProperties retryProperties) {
        long maxRetries = retryProperties.getMaxAttempts() - 1L;

        FixedBackOff backOff = new FixedBackOff(retryProperties.getBackoffMs(), maxRetries);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);

        return errorHandler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, OrderEvent> kafkaListenerContainerFactory(ConsumerFactory<String, OrderEvent> consumerFactory,
                                                                                              DefaultErrorHandler kafkaErrorHandler,
                                                                                              KafkaConsumerProperties consumerProperties,
                                                                                              KafkaProperties kafkaProperties) {

        ConcurrentKafkaListenerContainerFactory<String, OrderEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.setConcurrency(consumerProperties.concurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setObservationEnabled(kafkaProperties.getListener().isObservationEnabled());
        return factory;
    }
}