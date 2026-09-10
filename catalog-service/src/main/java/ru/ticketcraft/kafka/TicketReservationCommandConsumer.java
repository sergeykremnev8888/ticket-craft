package ru.ticketcraft.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import ru.ticketcraft.dto.ReleaseTicketCommand;
import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.service.TicketReservationService;

@Component
public class TicketReservationCommandConsumer {

    private final TicketReservationService reservationService;

    public TicketReservationCommandConsumer(
            TicketReservationService reservationService) {

        this.reservationService =
                reservationService;
    }

    @KafkaListener(
            topics = "${ticketcraft.kafka.topics.ticket-reservation-commands}",
            containerFactory = "ticketReservationCommandKafkaListenerContainerFactory")
    public void handle(Object command) {

        if (command instanceof ReserveTicketCommand reserveCommand) {

            reservationService.processReserveTicketCommand(reserveCommand);

            return;
        }

        if (command instanceof ReleaseTicketCommand releaseCommand) {

            reservationService.processReleaseTicketCommand(releaseCommand);

            return;
        }

        throw new IllegalArgumentException(
                "Unsupported ticket reservation command type: " + command.getClass().getName());
    }
}