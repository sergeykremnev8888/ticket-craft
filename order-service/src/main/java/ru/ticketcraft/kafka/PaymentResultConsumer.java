package ru.ticketcraft.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import ru.ticketcraft.service.PaymentResultProcessor;

@Component
public class PaymentResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultConsumer.class);

    private final PaymentResultProcessor processor;

    public PaymentResultConsumer(PaymentResultProcessor processor) {

        this.processor = processor;
    }

    @KafkaListener(topics = "${ticketcraft.kafka.topics.payment-results}", groupId = "order-payment-results", containerFactory = "paymentResultKafkaListenerContainerFactory")
    public void handle(Object event) {

        if (event instanceof PaymentSucceededEvent succeededEvent) {

            log.info("Received PaymentSucceededEvent " + "[messageId={}, orderId={}, paymentId={}]",
                    succeededEvent.messageId(), succeededEvent.orderId(), succeededEvent.paymentId());

            processor.process(succeededEvent);

            log.info("Processed PaymentSucceededEvent " + "[messageId={}, orderId={}]", succeededEvent.messageId(),
                    succeededEvent.orderId());

            return;
        }

        if (event instanceof PaymentFailedEvent failedEvent) {

            log.info("Received PaymentFailedEvent " + "[messageId={}, orderId={}, paymentId={}, reason={}]",
                    failedEvent.messageId(), failedEvent.orderId(), failedEvent.paymentId(), failedEvent.reason());

            processor.process(failedEvent);

            log.info("Processed PaymentFailedEvent " + "[messageId={}, orderId={}, reason={}]", failedEvent.messageId(),
                    failedEvent.orderId(), failedEvent.reason());

            return;
        }

        throw new IllegalArgumentException("Unsupported payment result type: " + event.getClass().getName());
    }
}