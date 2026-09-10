package ru.ticketcraft.payment.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.payment.gateway.PaymentResult;
import ru.ticketcraft.payment.service.PaymentService;

@Service
public class PaymentConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentConsumer.class);

    private final PaymentService paymentService;

    public PaymentConsumer(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @KafkaListener(
            id = "paymentConsumer",
            topics = "${ticketcraft.kafka.request-topic}"
    )
    public void listen(PaymentRequestedEvent event, Acknowledgment acknowledgment) {

        log.info("Получено PaymentRequestedEvent " + "[messageId={}, orderId={}]", event.messageId(), event.orderId());

        PaymentResult result = paymentService.process(event);
        acknowledgment.acknowledge();

        log.info("PaymentRequestedEvent обработан " + "[messageId={}, orderId={}, successful={}]", event.messageId(),
                event.orderId(), result.successful());
    }
}