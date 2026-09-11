package ru.ticketcraft.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import ru.ticketcraft.dto.EventSummaryResponse;
import ru.ticketcraft.model.Event;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {

    @Query("""
            SELECT new ru.ticketcraft.dto.EventSummaryResponse(
                e.id,
                e.title,
                e.description,
                e.eventDate,
                e.venue
            )
            FROM Event e
            ORDER BY e.eventDate ASC, e.id ASC
            """)
    List<EventSummaryResponse> findAllSummaries();

    @Query("""
            SELECT new ru.ticketcraft.dto.EventSummaryResponse(
                e.id,
                e.title,
                e.description,
                e.eventDate,
                e.venue
            )
            FROM Event e
            WHERE e.id = :eventId
            """)
    Optional<EventSummaryResponse> findSummaryById(@Param("eventId") UUID eventId);

    @EntityGraph(attributePaths = { "tickets" })
    @Query("SELECT e FROM Event e")
    List<Event> findAllWithTicketsGraph();

}