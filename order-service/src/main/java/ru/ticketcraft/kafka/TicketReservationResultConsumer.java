package ru.ticketcraft.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import ru.ticketcraft.dto.TicketConfirmationFailedEvent;
import ru.ticketcraft.dto.TicketConfirmedEvent;
import ru.ticketcraft.dto.TicketReleasedEvent;
import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketReservedEvent;
import ru.ticketcraft.service.TicketReservationResultProcessor;

@Component
public class TicketReservationResultConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(TicketReservationResultConsumer.class);

    private final TicketReservationResultProcessor processor;

    public TicketReservationResultConsumer(
            TicketReservationResultProcessor processor) {

        this.processor = processor;
    }

    @KafkaListener(
            topics = "${ticketcraft.kafka.topics.ticket-reservation-results}",
            groupId = "${ticketcraft.kafka.consumer.ticket-reservation-results-group-id}",
            containerFactory = "ticketReservationResultKafkaListenerContainerFactory")
    public void handle(ConsumerRecord<?, ?> record) {

        Object event = record.value();

        if (event instanceof TicketReservedEvent reservedEvent) {

            log.info(
                    "Received TicketReservedEvent "
                            + "[messageId={}, orderId={}, reservationId={}, ticketId={}]",
                    reservedEvent.messageId(),
                    reservedEvent.orderId(),
                    reservedEvent.reservationId(),
                    reservedEvent.ticketId());

            processor.process(reservedEvent);

            log.info(
                    "Processed TicketReservedEvent [messageId={}, orderId={}]",
                    reservedEvent.messageId(),
                    reservedEvent.orderId());

            return;
        }

        if (event instanceof TicketReservationFailedEvent failedEvent) {

            log.info(
                    "Received TicketReservationFailedEvent "
                            + "[messageId={}, orderId={}, reservationId={}, ticketId={}, reason={}]",
                    failedEvent.messageId(),
                    failedEvent.orderId(),
                    failedEvent.reservationId(),
                    failedEvent.ticketId(),
                    failedEvent.reason());

            processor.process(failedEvent);

            log.info(
                    "Processed TicketReservationFailedEvent "
                            + "[messageId={}, orderId={}, reason={}]",
                    failedEvent.messageId(),
                    failedEvent.orderId(),
                    failedEvent.reason());

            return;
        }

        if (event instanceof TicketConfirmedEvent confirmedEvent) {

            log.info(
                    "Received TicketConfirmedEvent "
                            + "[messageId={}, orderId={}, reservationId={}, ticketId={}]",
                    confirmedEvent.messageId(),
                    confirmedEvent.orderId(),
                    confirmedEvent.reservationId(),
                    confirmedEvent.ticketId());

            processor.process(confirmedEvent);

            log.info(
                    "Processed TicketConfirmedEvent "
                            + "[messageId={}, orderId={}]",
                    confirmedEvent.messageId(),
                    confirmedEvent.orderId());

            return;
        }

        if (event instanceof TicketConfirmationFailedEvent failedEvent) {

            log.warn(
                    "Received TicketConfirmationFailedEvent "
                            + "[messageId={}, orderId={}, reservationId={}, "
                            + "ticketId={}, reason={}]",
                    failedEvent.messageId(),
                    failedEvent.orderId(),
                    failedEvent.reservationId(),
                    failedEvent.ticketId(),
                    failedEvent.reason());

            processor.process(failedEvent);

            log.info(
                    "Processed TicketConfirmationFailedEvent "
                            + "[messageId={}, orderId={}, reason={}]",
                    failedEvent.messageId(),
                    failedEvent.orderId(),
                    failedEvent.reason());

            return;
        }

        if (event instanceof TicketReleasedEvent releasedEvent) {
            processor.process(releasedEvent);
            return;
        }

        if (event == null) {
            throw new IllegalArgumentException(
                    "Ticket reservation result payload must not be null");
        }

        throw new IllegalArgumentException(
                "Unsupported ticket reservation result event type: "
                        + event.getClass().getName());
    }
}