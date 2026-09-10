package ru.ticketcraft.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.service.TicketReservationService;

@Component
public class TicketReservationCommandConsumer {

    private final TicketReservationService reservationService;

    public TicketReservationCommandConsumer(TicketReservationService reservationService) {

        this.reservationService = reservationService;
    }

    @KafkaListener(topics = "${ticketcraft.kafka.topics.ticket-reservation-commands}", groupId = "catalog-ticket-reservation")
    public void handle(ReserveTicketCommand command) {
        reservationService.processReserveTicketCommand(command);
    }
}