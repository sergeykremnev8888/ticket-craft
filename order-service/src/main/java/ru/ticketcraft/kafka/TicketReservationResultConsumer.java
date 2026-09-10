package ru.ticketcraft.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import ru.ticketcraft.dto.TicketReleasedEvent;
import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketReservedEvent;
import ru.ticketcraft.service.TicketReservationResultProcessor;

@Component
public class TicketReservationResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(TicketReservationResultConsumer.class);

    private final TicketReservationResultProcessor processor;

    public TicketReservationResultConsumer(TicketReservationResultProcessor processor) {

        this.processor = processor;
    }

    @KafkaListener(topics = "${ticketcraft.kafka.topics.ticket-reservation-results}", containerFactory = "ticketReservationResultKafkaListenerContainerFactory")
    public void handle(Object event) {

        if (event instanceof TicketReservedEvent ticketReservedEvent) {

            log.info("Received TicketReservedEvent " + "[messageId={}, orderId={}, reservationId={}, ticketId={}]",
                    ticketReservedEvent.messageId(), ticketReservedEvent.orderId(), ticketReservedEvent.reservationId(),
                    ticketReservedEvent.ticketId());

            processor.process(ticketReservedEvent);

            log.info("Processed TicketReservedEvent " + "[messageId={}, orderId={}]", ticketReservedEvent.messageId(),
                    ticketReservedEvent.orderId());

            return;
        }

        if (event instanceof TicketReservationFailedEvent failedEvent) {

            log.info(
                    "Received TicketReservationFailedEvent "
                            + "[messageId={}, orderId={}, reservationId={}, ticketId={}, reason={}]",
                    failedEvent.messageId(), failedEvent.orderId(), failedEvent.reservationId(), failedEvent.ticketId(),
                    failedEvent.reason());

            processor.process(failedEvent);

            log.info("Processed TicketReservationFailedEvent " + "[messageId={}, orderId={}, reason={}]",
                    failedEvent.messageId(), failedEvent.orderId(), failedEvent.reason());

            return;
        }

        if (event instanceof TicketReleasedEvent releasedEvent) {

            processor.process(releasedEvent);

            return;
        }

        throw new IllegalArgumentException("Unsupported ticket reservation result type: " + event.getClass().getName());
    }
}