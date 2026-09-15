package ru.ticketcraft.payment.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import ru.ticketcraft.dto.RefundPaymentCommand;
import ru.ticketcraft.payment.gateway.PaymentResult;
import ru.ticketcraft.payment.service.PaymentService;

@Service
public class PaymentRefundConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundConsumer.class);

    private final PaymentService paymentService;

    public PaymentRefundConsumer(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @KafkaListener(id = "paymentRefundConsumer", topics = "${ticketcraft.kafka.command-topic}", groupId = "${spring.kafka.consumer.group-id}", containerFactory = "paymentCommandKafkaListenerContainerFactory")
    public void listen(RefundPaymentCommand command, Acknowledgment acknowledgment) {

        log.info("Получен RefundPaymentCommand " + "[messageId={}, orderId={}, paymentId={}, reason={}]",
                command.messageId(), command.orderId(), command.paymentId(), command.reason());

        PaymentResult result = paymentService.process(command);

        acknowledgment.acknowledge();

        log.info("RefundPaymentCommand обработан " + "[messageId={}, orderId={}, paymentId={}, successful={}]",
                command.messageId(), command.orderId(), command.paymentId(), result.successful());
    }
}