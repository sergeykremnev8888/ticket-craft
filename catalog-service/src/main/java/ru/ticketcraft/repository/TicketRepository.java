package ru.ticketcraft.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import ru.ticketcraft.model.Ticket;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    /**
     * При высокой конкуренции PostgreSQL сам гарантирует, что для одного ticket
     * только один конкурентный запрос получит affectedRows = 1. Это гораздо лучше
     * для hot path, чем удерживать row lock на время транзакции.
     */
    @Modifying
    @Query("""
        UPDATE Ticket t
           SET t.status = ru.ticketcraft.dto.TicketStatus.RESERVED,
               t.reservationId = :reservationId,
               t.reservedUntil = :reservedUntil,
               t.version = t.version + 1,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :ticketId
           AND t.status = ru.ticketcraft.dto.TicketStatus.AVAILABLE
        """)
    int reserveTicket(
            @Param("ticketId") UUID ticketId,
            @Param("reservationId") UUID reservationId,
            @Param("reservedUntil") Instant reservedUntil
    );

}
