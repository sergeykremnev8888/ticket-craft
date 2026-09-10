package ru.ticketcraft.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.config.ReservationProperties;
import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.exception.TicketAlreadyReservedException;
import ru.ticketcraft.exception.TicketNotFoundException;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.TicketRepository;

@Service
public class TicketReservationService {

    private final TicketRepository ticketRepository;
    private final Duration reservationDuration;

    public TicketReservationService(TicketRepository ticketRepository, ReservationProperties properties) {
        this.ticketRepository = ticketRepository;
        this.reservationDuration = properties.duration();
    }

    @Transactional
    public UUID reserveTicket(UUID ticketId) {
        UUID reservationId = UUID.randomUUID();
        reserve(ticketId, reservationId);
        return reservationId;
    }

    @Transactional
    public void reserveTicket(UUID ticketId, UUID reservationId) {
        reserve(ticketId, reservationId);
    }

    private void reserve(UUID ticketId, UUID reservationId) {
        Instant reservedUntil = Instant.now().plus(reservationDuration);
        int updated = ticketRepository.reserveTicket(ticketId, reservationId, reservedUntil);
        if (updated == 1) {
            return;
        }

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found: " + ticketId));
        /*
         * Kafka redelivery той же Saga-команды.
         *
         * Билет уже зарезервирован именно нами — считаем операцию успешно выполненной.
         */
        if (ticket.getStatus() == TicketStatus.RESERVED && reservationId.equals(ticket.getReservationId())) {
            return;
        }

        throw new TicketAlreadyReservedException("Ticket is already reserved: " + ticketId);
    }
}