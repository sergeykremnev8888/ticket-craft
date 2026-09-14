package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.EventSummaryResponse;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.repository.EventRepository;

@SpringBootTest(properties = {
        "ticketcraft.outbox.publisher.enabled=false",

        "spring.data.redis.url=redis://127.0.0.1:1",
        "spring.data.redis.connect-timeout=100ms",
        "spring.data.redis.timeout=100ms",

        "catalog.cache.events-ttl=5m",
        "ticketcraft.rate-limit.enabled=false",

        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.enabled=false"
})
@Import(EventCatalogCacheFallbackIntegrationTest.TestContainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
        Event event = new Event();

        event.setTitle("Redis Failure Conference");
        event.setDescription("Catalog must work when Redis is unavailable");
        event.setEventDate(Instant.parse("2026-12-10T12:00:00Z"));
        event.setVenue("Fallback Hall");

        Event savedEvent = eventRepository.saveAndFlush(event);

        clearInvocations(eventRepository);

        List<EventSummaryResponse> result = eventCatalogService.getEvents();

        assertThat(result).hasSize(1);

        EventSummaryResponse response = result.getFirst();

        assertThat(response.id()).isEqualTo(savedEvent.getId());

        assertThat(response.title()).isEqualTo("Redis Failure Conference");

        assertThat(response.description()).isEqualTo("Catalog must work when Redis is unavailable");

        assertThat(response.eventDate()).isEqualTo(Instant.parse("2026-12-10T12:00:00Z"));

        assertThat(response.venue()).isEqualTo("Fallback Hall");

        /*
         * Redis GET завершается ошибкой.
         *
         * LoggingCacheErrorHandler должен обработать ошибку cache read, после
         * чего @Cacheable выполнит настоящий метод и загрузит данные из PostgreSQL.
         */
        verify(eventRepository, times(1)).findAllSummaries();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestContainersConfiguration {

        @Bean
        @ServiceConnection
        @SuppressWarnings("resource")
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("catalog_db").withUsername("postgres")
                    .withPassword("postgres");
        }
    }
}
