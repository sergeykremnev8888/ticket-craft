package ru.ticketcraft.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ru.ticketcraft.service.TicketReservationExpirationService;

@Component
public class TicketReservationExpirationScheduler {

    private final TicketReservationExpirationService expirationService;

    public TicketReservationExpirationScheduler(TicketReservationExpirationService expirationService) {
        this.expirationService = expirationService;
    }

    @Scheduled(fixedDelayString = "${catalog.reservation.expiration-check-interval:10s}")
    public void releaseExpiredReservations() {
        expirationService.releaseExpiredReservations();
    }
}
