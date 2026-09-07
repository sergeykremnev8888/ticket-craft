package ru.ticketcraft.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.exception.TicketNotFoundException;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.TicketRepository;

@Service
public class TicketReservationService {

    private final TicketRepository ticketRepository;

    public TicketReservationService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Transactional
    public boolean reserveTicket(UUID ticketId) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new TicketNotFoundException("Билет не найден: " + ticketId));

        if (ticket.getStatus() != TicketStatus.AVAILABLE) {
            return false; // Уже куплен другим пользователем
        }

        ticket.setStatus(TicketStatus.RESERVED);
        ticketRepository.save(ticket);
        return true;
    }
}
