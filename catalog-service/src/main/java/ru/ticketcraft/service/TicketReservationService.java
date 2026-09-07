package ru.ticketcraft.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.config.ReservationProperties;
import ru.ticketcraft.exception.TicketAlreadyReservedException;
import ru.ticketcraft.exception.TicketNotFoundException;
import ru.ticketcraft.repository.TicketRepository;

@Service
public class TicketReservationService {

    private final ReservationProperties properties;
    private final TicketRepository ticketRepository;

    public TicketReservationService(ReservationProperties properties, TicketRepository ticketRepository) {
        this.properties = properties;
        this.ticketRepository = ticketRepository;
    }

    @Transactional
    public UUID reserveTicket(UUID ticketId) {
        UUID reservationId = UUID.randomUUID();
        Instant reservedUntil = Instant.now().plus(properties.duration());

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
