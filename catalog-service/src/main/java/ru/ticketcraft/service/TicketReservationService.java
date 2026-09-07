package ru.ticketcraft.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.exception.TicketAlreadyReservedException;
import ru.ticketcraft.exception.TicketNotFoundException;
import ru.ticketcraft.repository.TicketRepository;

@Service
public class TicketReservationService {

    private static final Duration RESERVATION_DURATION = Duration.ofMinutes(10);

    private final TicketRepository ticketRepository;

    public TicketReservationService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Transactional
    public UUID reserveTicket(UUID ticketId) {
        UUID reservationId = UUID.randomUUID();
        Instant reservedUntil = Instant.now().plus(RESERVATION_DURATION);

        int updatedRows = ticketRepository.reserveTicket(ticketId, reservationId, reservedUntil);
        if (updatedRows == 1) {
            return reservationId;
        }

        if (!ticketRepository.existsById(ticketId)) {
            throw new TicketNotFoundException("Ticket is not found: " + ticketId);
        }

        throw new TicketAlreadyReservedException("Ticket is already reserved: " + ticketId);
    }
}
