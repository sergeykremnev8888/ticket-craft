package ru.ticketcraft.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.repository.TicketRepository;

@Service
public class TicketReservationExpirationService {

    private final TicketRepository ticketRepository;

    public TicketReservationExpirationService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Transactional
    public int releaseExpiredReservations() {
        return ticketRepository.releaseExpiredReservations(Instant.now());
    }
}
