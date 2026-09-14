package ru.ticketcraft.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.EventSummaryResponse;
import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.model.Ticket;

@Testcontainers
@SpringBootTest(properties = {
        "ticketcraft.outbox.publisher.enabled=false",
        "spring.cache.type=none",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.enabled=false"
})
class EventRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("catalog_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private EventRepository eventRepository;

    @AfterEach
    void cleanUp() {
        eventRepository.deleteAll();
    }

    @Test
    void shouldReturnEventSummariesOrderedByEventDateAndId() {
        Event laterEvent = createEvent("Later Event", Instant.parse("2026-12-20T10:00:00Z"));

        Event earlierEvent = createEvent("Earlier Event", Instant.parse("2026-10-20T10:00:00Z"));

        eventRepository.saveAndFlush(laterEvent);
        eventRepository.saveAndFlush(earlierEvent);

        List<EventSummaryResponse> result = eventRepository.findAllSummaries();

        assertThat(result).hasSize(2);

        assertThat(result.get(0).title()).isEqualTo("Earlier Event");

        assertThat(result.get(1).title()).isEqualTo("Later Event");
    }

    @Test
    void shouldUseIdAsTieBreakerWhenEventDatesAreEqual() {
        Instant eventDate = Instant.parse("2026-11-15T18:00:00Z");

        Event firstEvent = createEvent("Event A", eventDate);
        Event secondEvent = createEvent("Event B", eventDate);

        Event savedFirstEvent = eventRepository.saveAndFlush(firstEvent);
        Event savedSecondEvent = eventRepository.saveAndFlush(secondEvent);

        List<UUID> expectedIds = List.of(savedFirstEvent.getId(), savedSecondEvent.getId()).stream()
                .sorted(Comparator.comparing(UUID::toString)).toList();

        List<EventSummaryResponse> result = eventRepository.findAllSummaries();

        assertThat(result).hasSize(2);

        assertThat(result).extracting(EventSummaryResponse::eventDate).containsOnly(eventDate);

        assertThat(result).extracting(EventSummaryResponse::id).containsExactlyElementsOf(expectedIds);
    }

    @Test
    void shouldReturnEventWithTicketsById() {
        Event event = createEvent("Java Highload Conference", Instant.parse("2026-12-01T12:00:00Z"));

        Ticket firstTicket = createTicket(event, "A-01", new BigDecimal("1500.00"), TicketStatus.AVAILABLE);

        Ticket secondTicket = createTicket(event, "A-02", new BigDecimal("1750.00"), TicketStatus.RESERVED);

        event.getTickets().add(firstTicket);
        event.getTickets().add(secondTicket);

        Event savedEvent = eventRepository.saveAndFlush(event);

        Optional<Event> result = eventRepository.findByIdWithTickets(savedEvent.getId());

        assertThat(result).isPresent();

        Event loadedEvent = result.orElseThrow();

        assertThat(loadedEvent.getId()).isEqualTo(savedEvent.getId());

        assertThat(loadedEvent.getTitle()).isEqualTo("Java Highload Conference");

        assertThat(loadedEvent.getDescription()).isEqualTo("Repository projection test");

        assertThat(loadedEvent.getEventDate()).isEqualTo(Instant.parse("2026-12-01T12:00:00Z"));

        assertThat(loadedEvent.getVenue()).isEqualTo("Tashkent IT Park");

        assertThat(loadedEvent.getTickets()).hasSize(2);

        assertThat(loadedEvent.getTickets()).extracting(Ticket::getSeatNumber).containsExactlyInAnyOrder("A-01",
                "A-02");

        assertThat(loadedEvent.getTickets()).filteredOn(ticket -> ticket.getSeatNumber().equals("A-01")).singleElement()
                .satisfies(ticket -> {
                    assertThat(ticket.getPrice()).isEqualByComparingTo("1500.00");

                    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.AVAILABLE);

                    assertThat(ticket.getEvent().getId()).isEqualTo(savedEvent.getId());
                });

        assertThat(loadedEvent.getTickets()).filteredOn(ticket -> ticket.getSeatNumber().equals("A-02")).singleElement()
                .satisfies(ticket -> {
                    assertThat(ticket.getPrice()).isEqualByComparingTo("1750.00");

                    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RESERVED);

                    assertThat(ticket.getEvent().getId()).isEqualTo(savedEvent.getId());
                });
    }

    @Test
    void shouldReturnEmptyWhenEventWithTicketsDoesNotExist() {
        Optional<Event> result = eventRepository.findByIdWithTickets(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    private Event createEvent(String title, Instant eventDate) {
        Event event = new Event();

        event.setTitle(title);
        event.setDescription("Repository projection test");
        event.setEventDate(eventDate);
        event.setVenue("Tashkent IT Park");

        return event;
    }

    private Ticket createTicket(Event event, String seatNumber, BigDecimal price, TicketStatus status) {
        Ticket ticket = new Ticket();

        ticket.setEvent(event);
        ticket.setSeatNumber(seatNumber);
        ticket.setPrice(price);
        ticket.setStatus(status);

        return ticket;
    }
}