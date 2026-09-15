package ru.ticketcraft.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import ru.ticketcraft.dto.ConfirmTicketCommand;
import ru.ticketcraft.dto.ReleaseTicketCommand;
import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.service.TicketReservationService;

@Component
public class TicketReservationCommandConsumer {

    private final TicketReservationService reservationService;

    public TicketReservationCommandConsumer(
            TicketReservationService reservationService) {

        this.reservationService = reservationService;
    }

    @KafkaListener(
            topics = "${ticketcraft.kafka.topics.ticket-reservation-commands}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "ticketReservationCommandKafkaListenerContainerFactory")
    public void handle(ConsumerRecord<?, ?> record) {

        Object command = record.value();

        if (command instanceof ReserveTicketCommand reserveCommand) {
            reservationService.processReserveTicketCommand(reserveCommand);
            return;
        }

        if (command instanceof ConfirmTicketCommand confirmCommand) {
            reservationService.processConfirmTicketCommand(confirmCommand);
            return;
        }

        if (command instanceof ReleaseTicketCommand releaseCommand) {
            reservationService.processReleaseTicketCommand(releaseCommand);
            return;
        }

        if (command == null) {
            throw new IllegalArgumentException(
                    "Ticket reservation command payload must not be null");
        }

        throw new IllegalArgumentException(
                "Unsupported ticket reservation command type: "
                        + command.getClass().getName());
    }
}