package ru.ticketcraft.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.TicketRepository;

@Service
public class TicketReservationService {

    private final TicketRepository ticketRepository;

    public TicketReservationService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Transactional
    public boolean reserveTicket(Long ticketId) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Билет не найден: " + ticketId));

        if (!ticket.isAvailable()) {
            return false; // Уже куплен другим пользователем
        }

        ticket.setAvailable(false);
        ticketRepository.save(ticket);
        return true;
    }
}
