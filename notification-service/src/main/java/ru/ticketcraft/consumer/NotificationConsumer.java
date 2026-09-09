package ru.ticketcraft.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.service.IdempotentNotificationProcessor;

@Service
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final IdempotentNotificationProcessor processor;

    public NotificationConsumer(IdempotentNotificationProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(
            id = "notificationConsumer",
            topics = "order-events",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void listen(@Payload OrderEvent event, @Header(KafkaHeaders.RECEIVED_KEY) String messageKey,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition, @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.info("Получено сообщение из Kafka [partition={}, offset={}, key={}, messageId={}]", partition, offset,
                messageKey, event.getMessageId());

        processor.process(event);
        acknowledgment.acknowledge();

        log.info("Сообщение обработано и подтверждено [messageId={}, orderId={}]", event.getMessageId(),
                event.getOrderId());
    }
}