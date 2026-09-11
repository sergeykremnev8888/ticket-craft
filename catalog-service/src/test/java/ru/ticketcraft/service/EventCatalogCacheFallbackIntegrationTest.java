package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.EventSummaryResponse;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.repository.EventRepository;

@SpringBootTest(properties = { "ticketcraft.outbox.publisher.enabled=false",

        /*
         * Намеренно указываем недоступный Redis.
         *
         * Redis не является source of truth, поэтому Catalog API должен продолжить
         * работу через PostgreSQL.
         */
        "spring.data.redis.url=redis://127.0.0.1:1",

        /*
         * Не заставляем integration test ждать секунды.
         */
        "spring.data.redis.connect-timeout=100ms", "spring.data.redis.timeout=100ms",

        "catalog.cache.events-ttl=5m" })
@Import(EventCatalogCacheFallbackIntegrationTest.TestContainersConfiguration.class)
class EventCatalogCacheFallbackIntegrationTest {

    @Autowired
    private EventCatalogService eventCatalogService;

    @MockitoSpyBean
    private EventRepository eventRepository;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();

        clearInvocations(eventRepository);
    }

    @AfterEach
    void cleanUp() {
        eventRepository.deleteAll();

        clearInvocations(eventRepository);
    }

    @Test
    void shouldFallBackToPostgresWhenRedisIsUnavailable() {

        // Given
        Event event = new Event();

        event.setTitle("Redis Failure Conference");
        event.setDescription("Catalog must work when Redis is unavailable");
        event.setEventDate(Instant.parse("2026-12-10T12:00:00Z"));
        event.setVenue("Fallback Hall");

        Event savedEvent = eventRepository.saveAndFlush(event);

        clearInvocations(eventRepository);

        // When
        List<EventSummaryResponse> result = eventCatalogService.getEvents();

        // Then
        assertThat(result).hasSize(1);

        EventSummaryResponse response = result.getFirst();

        assertThat(response.id()).isEqualTo(savedEvent.getId());

        assertThat(response.title()).isEqualTo("Redis Failure Conference");

        assertThat(response.description()).isEqualTo("Catalog must work when Redis is unavailable");

        assertThat(response.eventDate()).isEqualTo(Instant.parse("2026-12-10T12:00:00Z"));

        assertThat(response.venue()).isEqualTo("Fallback Hall");

        /*
         * Redis GET завершится ошибкой.
         *
         * LoggingCacheErrorHandler обязан проглотить ошибку, после чего @Cacheable
         * выполнит настоящий метод, который прочитает данные из PostgreSQL.
         */
        verify(eventRepository, times(1)).findAllSummaries();
    }

    @Test
    void shouldFallBackToPostgresForEventByIdWhenRedisIsUnavailable() {

        // Given
        Event event = new Event();

        event.setTitle("Redis Failure Single Event");
        event.setDescription("Single event must be loaded from PostgreSQL when Redis is unavailable");
        event.setEventDate(Instant.parse("2026-12-15T15:00:00Z"));
        event.setVenue("Fallback Event Hall");

        Event savedEvent = eventRepository.saveAndFlush(event);

        UUID eventId = savedEvent.getId();

        clearInvocations(eventRepository);

        // When
        EventSummaryResponse response = eventCatalogService.getEvent(eventId);

        // Then
        assertThat(response.id()).isEqualTo(eventId);

        assertThat(response.title()).isEqualTo("Redis Failure Single Event");

        assertThat(response.description())
                .isEqualTo("Single event must be loaded from PostgreSQL when Redis is unavailable");

        assertThat(response.eventDate())
                .isEqualTo(Instant.parse("2026-12-15T15:00:00Z"));

        assertThat(response.venue()).isEqualTo("Fallback Event Hall");

        /*
         * Redis GET завершится ошибкой.
         *
         * LoggingCacheErrorHandler обязан проглотить ошибку,
         * после чего @Cacheable выполнит настоящий метод
         * и прочитает event из PostgreSQL.
         */
        verify(eventRepository, times(1)).findSummaryById(eventId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestContainersConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("catalog_db").withUsername("postgres")
                    .withPassword("postgres");
        }

    }
}