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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
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
            @Param("reservedUntil") Instant reservedUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Ticket t
               SET t.status = ru.ticketcraft.dto.TicketStatus.SOLD,
                   t.reservedUntil = NULL,
                   t.version = t.version + 1,
                   t.updatedAt = CURRENT_TIMESTAMP
             WHERE t.id = :ticketId
               AND t.status = ru.ticketcraft.dto.TicketStatus.RESERVED
               AND t.reservationId = :reservationId
               AND t.reservedUntil > :now
            """)
    int confirmTicket(
            @Param("ticketId") UUID ticketId,
            @Param("reservationId") UUID reservationId,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Ticket t
               SET t.status = ru.ticketcraft.dto.TicketStatus.AVAILABLE,
                   t.reservationId = NULL,
                   t.reservedUntil = NULL,
                   t.version = t.version + 1,
                   t.updatedAt = CURRENT_TIMESTAMP
             WHERE t.status = ru.ticketcraft.dto.TicketStatus.RESERVED
               AND t.reservedUntil <= :now
            """)
    int releaseExpiredReservations(
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Ticket t
               SET t.status = ru.ticketcraft.dto.TicketStatus.AVAILABLE,
                   t.reservationId = NULL,
                   t.reservedUntil = NULL,
                   t.version = t.version + 1,
                   t.updatedAt = CURRENT_TIMESTAMP
             WHERE t.id = :ticketId
               AND t.status = ru.ticketcraft.dto.TicketStatus.RESERVED
               AND t.reservationId = :reservationId
            """)
    int releaseTicket(
            @Param("ticketId") UUID ticketId,
            @Param("reservationId") UUID reservationId);
}