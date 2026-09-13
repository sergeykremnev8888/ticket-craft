package ru.ticketcraft.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import ru.ticketcraft.model.Event;

@Testcontainers
@SpringBootTest(properties = {
        "ticketcraft.outbox.publisher.enabled=false",
        "spring.cache.type=none",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.enabled=false" })
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
    void shouldReturnSummaryByEventId() {

        Event event = createEvent("Java Highload Conference", Instant.parse("2026-12-01T12:00:00Z"));

        Event savedEvent = eventRepository.saveAndFlush(event);

        Optional<EventSummaryResponse> result = eventRepository.findSummaryById(savedEvent.getId());

        assertThat(result).isPresent();

        EventSummaryResponse response = result.orElseThrow();

        assertThat(response.id()).isEqualTo(savedEvent.getId());

        assertThat(response.title()).isEqualTo("Java Highload Conference");

        assertThat(response.description()).isEqualTo("Repository projection test");

        assertThat(response.eventDate()).isEqualTo(Instant.parse("2026-12-01T12:00:00Z"));

        assertThat(response.venue()).isEqualTo("Tashkent IT Park");
    }

    @Test
    void shouldReturnEmptySummaryForUnknownEvent() {

        Optional<EventSummaryResponse> result = eventRepository.findSummaryById(UUID.randomUUID());

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
}