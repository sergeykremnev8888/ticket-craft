package ru.ticketcraft.payment.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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

import ru.ticketcraft.dto.PaymentRequestedEvent;

@Configuration
@EnableKafka
public class PaymentKafkaConfig {

    private final PaymentKafkaProperties kafkaProperties;
    private final PaymentRetryProperties retryProperties;

    public PaymentKafkaConfig(PaymentKafkaProperties kafkaProperties, PaymentRetryProperties retryProperties) {
        this.kafkaProperties = kafkaProperties;
        this.retryProperties = retryProperties;
    }

    @Bean
    ProducerFactory<String, Object> paymentProducerFactory(KafkaProperties springKafkaProperties) {
        Map<String, Object> properties = new HashMap<>(springKafkaProperties.buildProducerProperties());

        Map<Class<?>, Serializer<?>> serializers = new LinkedHashMap<>();

        serializers.put(byte[].class, new ByteArraySerializer());
        serializers.put(Object.class, new JacksonJsonSerializer<>());

        DelegatingByTypeSerializer valueSerializer = new DelegatingByTypeSerializer(serializers, true);

        return new DefaultKafkaProducerFactory<>(properties, new StringSerializer(), valueSerializer);
    }

    @Bean
    KafkaTemplate<String, Object> paymentKafkaTemplate(ProducerFactory<String, Object> paymentProducerFactory) {
        return new KafkaTemplate<>(paymentProducerFactory);
    }

    @Bean
    ConsumerFactory<String, PaymentRequestedEvent> paymentConsumerFactory(KafkaProperties springKafkaProperties) {
        Map<String, Object> properties = new HashMap<>(springKafkaProperties.buildConsumerProperties());

        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        properties.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class);
        properties.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, PaymentRequestedEvent.class.getName());
        properties.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "ru.ticketcraft.dto");

        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    DeadLetterPublishingRecoverer paymentDeadLetterPublishingRecoverer(
            KafkaTemplate<String, Object> paymentKafkaTemplate) {
        return new DeadLetterPublishingRecoverer(paymentKafkaTemplate,
                (record, exception) -> new TopicPartition(kafkaProperties.getDltTopic(), record.partition()));
    }

    @Bean
    DefaultErrorHandler paymentErrorHandler(DeadLetterPublishingRecoverer paymentDeadLetterPublishingRecoverer) {
        FixedBackOff backOff = new FixedBackOff(retryProperties.getBackOff().toMillis(),
                retryProperties.getMaxAttempts() - 1L);

        return new DefaultErrorHandler(paymentDeadLetterPublishingRecoverer, backOff);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, PaymentRequestedEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, PaymentRequestedEvent> paymentConsumerFactory,
            DefaultErrorHandler paymentErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, PaymentRequestedEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(paymentConsumerFactory);
        factory.setCommonErrorHandler(paymentErrorHandler);
        factory.setConcurrency(kafkaProperties.getConcurrency());
        factory.getContainerProperties().setPollTimeout(kafkaProperties.getPollTimeout().toMillis());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        return factory;
    }
}