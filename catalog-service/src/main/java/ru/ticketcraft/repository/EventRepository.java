package ru.ticketcraft.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import ru.ticketcraft.model.Event;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {

    List<Event> findAllByOrderByEventDateAsc();

    List<Event> findAll();

    @EntityGraph(attributePaths = { "tickets" })
    @Query("SELECT e FROM Event e")
    List<Event> findAllWithTicketsGraph();
}