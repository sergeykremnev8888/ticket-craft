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

import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.dto.RefundPaymentCommand;

@Configuration
@EnableKafka
public class PaymentKafkaConfig {

    private final PaymentKafkaProperties paymentKafkaProperties;
    private final PaymentRetryProperties paymentRetryProperties;

    public PaymentKafkaConfig(
            PaymentKafkaProperties paymentKafkaProperties,
            PaymentRetryProperties paymentRetryProperties) {

        this.paymentKafkaProperties = paymentKafkaProperties;
        this.paymentRetryProperties = paymentRetryProperties;
    }

    @Bean
    ProducerFactory<String, Object> paymentProducerFactory(
            KafkaProperties springKafkaProperties) {

        Map<String, Object> properties =
                new HashMap<>(
                        springKafkaProperties.buildProducerProperties());

        Map<Class<?>, Serializer<?>> serializers =
                new LinkedHashMap<>();

        serializers.put(byte[].class, new ByteArraySerializer());
        serializers.put(Object.class, new JacksonJsonSerializer<>());

        DelegatingByTypeSerializer valueSerializer =
                new DelegatingByTypeSerializer(serializers, true);

        return new DefaultKafkaProducerFactory<>(
                properties,
                new StringSerializer(),
                valueSerializer);
    }

    @Bean
    KafkaTemplate<String, Object> paymentKafkaTemplate(
            @Qualifier("paymentProducerFactory")
            ProducerFactory<String, Object> paymentProducerFactory,
            KafkaProperties kafkaProperties) {

        KafkaTemplate<String, Object> kafkaTemplate =
                new KafkaTemplate<>(paymentProducerFactory);

        kafkaTemplate.setObservationEnabled(
                kafkaProperties.getTemplate().isObservationEnabled());

        return kafkaTemplate;
    }

    @Bean
    ConsumerFactory<String, PaymentRequestedEvent> paymentConsumerFactory(
            KafkaProperties springKafkaProperties) {

        Map<String, Object> properties =
                consumerProperties(
                        springKafkaProperties,
                        PaymentRequestedEvent.class);

        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    ConsumerFactory<String, RefundPaymentCommand>
            paymentCommandConsumerFactory(
                    KafkaProperties springKafkaProperties) {

        Map<String, Object> properties =
                consumerProperties(
                        springKafkaProperties,
                        RefundPaymentCommand.class);

        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    DeadLetterPublishingRecoverer paymentDeadLetterPublishingRecoverer(
            @Qualifier("paymentKafkaTemplate")
            KafkaTemplate<String, Object> paymentKafkaTemplate) {

        return new DeadLetterPublishingRecoverer(
                paymentKafkaTemplate,
                (record, exception) ->
                        new TopicPartition(
                                paymentKafkaProperties.getDltTopic(),
                                record.partition()));
    }

    @Bean
    DeadLetterPublishingRecoverer
            paymentCommandDeadLetterPublishingRecoverer(
                    @Qualifier("paymentKafkaTemplate")
                    KafkaTemplate<String, Object> paymentKafkaTemplate) {

        return new DeadLetterPublishingRecoverer(
                paymentKafkaTemplate,
                (record, exception) ->
                        new TopicPartition(
                                paymentKafkaProperties.getCommandDltTopic(),
                                record.partition()));
    }

    @Bean
    DefaultErrorHandler paymentErrorHandler(
            @Qualifier("paymentDeadLetterPublishingRecoverer")
            DeadLetterPublishingRecoverer recoverer) {

        return errorHandler(recoverer);
    }

    @Bean
    DefaultErrorHandler paymentCommandErrorHandler(
            @Qualifier("paymentCommandDeadLetterPublishingRecoverer")
            DeadLetterPublishingRecoverer recoverer) {

        return errorHandler(recoverer);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, PaymentRequestedEvent>
            kafkaListenerContainerFactory(
                    @Qualifier("paymentConsumerFactory")
                    ConsumerFactory<String, PaymentRequestedEvent> consumerFactory,
                    @Qualifier("paymentErrorHandler")
                    DefaultErrorHandler errorHandler,
                    KafkaProperties kafkaProperties) {

        ConcurrentKafkaListenerContainerFactory<String, PaymentRequestedEvent>
                factory =
                        new ConcurrentKafkaListenerContainerFactory<>();

        configureFactory(
                factory,
                consumerFactory,
                errorHandler,
                kafkaProperties);

        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, RefundPaymentCommand>
            paymentCommandKafkaListenerContainerFactory(
                    @Qualifier("paymentCommandConsumerFactory")
                    ConsumerFactory<String, RefundPaymentCommand> consumerFactory,
                    @Qualifier("paymentCommandErrorHandler")
                    DefaultErrorHandler errorHandler,
                    KafkaProperties kafkaProperties) {

        ConcurrentKafkaListenerContainerFactory<String, RefundPaymentCommand>
                factory =
                        new ConcurrentKafkaListenerContainerFactory<>();

        configureFactory(
                factory,
                consumerFactory,
                errorHandler,
                kafkaProperties);

        return factory;
    }

    private Map<String, Object> consumerProperties(
            KafkaProperties springKafkaProperties,
            Class<?> valueType) {

        Map<String, Object> properties =
                new HashMap<>(
                        springKafkaProperties.buildConsumerProperties());

        properties.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);

        properties.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ErrorHandlingDeserializer.class);

        properties.put(
                ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                JacksonJsonDeserializer.class);

        properties.put(
                JacksonJsonDeserializer.VALUE_DEFAULT_TYPE,
                valueType.getName());

        properties.put(
                JacksonJsonDeserializer.TRUSTED_PACKAGES,
                "ru.ticketcraft.dto");

        return properties;
    }

    private DefaultErrorHandler errorHandler(
            DeadLetterPublishingRecoverer recoverer) {

        FixedBackOff backOff =
                new FixedBackOff(
                        paymentRetryProperties.getBackOff().toMillis(),
                        paymentRetryProperties.getMaxAttempts() - 1L);

        return new DefaultErrorHandler(recoverer, backOff);
    }

    private <T> void configureFactory(
            ConcurrentKafkaListenerContainerFactory<String, T> factory,
            ConsumerFactory<String, T> consumerFactory,
            DefaultErrorHandler errorHandler,
            KafkaProperties kafkaProperties) {

        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(
                paymentKafkaProperties.getConcurrency());

        factory.getContainerProperties().setPollTimeout(
                paymentKafkaProperties.getPollTimeout().toMillis());

        factory.getContainerProperties().setAckMode(
                ContainerProperties.AckMode.MANUAL);

        factory.getContainerProperties().setObservationEnabled(
                kafkaProperties.getListener().isObservationEnabled());
    }
}