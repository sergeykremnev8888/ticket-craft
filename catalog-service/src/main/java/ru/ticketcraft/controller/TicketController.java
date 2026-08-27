package ru.ticketcraft.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.ticketcraft.service.TicketReservationService;

@RestController
@RequestMapping("/api/v1/catalog/tickets")
public class TicketController {

    private final TicketReservationService reservationService;

    public TicketController(TicketReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/{ticketId}/reserve")
    public ResponseEntity<Void> reserveTicket(@PathVariable Long ticketId) {
        boolean reserved = reservationService.reserveTicket(ticketId);
        if (reserved) {
            return ResponseEntity.ok().build(); // 200 OK
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT).build(); // 409 Conflict
        }
    }
}
