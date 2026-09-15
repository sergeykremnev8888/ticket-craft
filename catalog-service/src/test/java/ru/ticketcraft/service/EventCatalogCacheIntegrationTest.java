package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import ru.ticketcraft.dto.EventSummaryResponse;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.repository.EventRepository;
import ru.ticketcraft.support.CatalogTestContainersConfiguration;

@SpringBootTest(properties = {
        "ticketcraft.outbox.publisher.enabled=false",
        "catalog.cache.events-ttl=5m",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.enabled=false"
})
@Import({
        CatalogTestContainersConfiguration.class,
        EventCatalogCacheIntegrationTest.StrictCacheTestConfiguration.class
})
class EventCatalogCacheIntegrationTest {

    private static final String EVENTS_CACHE_KEY = "ticketcraft:catalog:events::all";

    @Autowired
    private EventCatalogService eventCatalogService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void setUp() {
        clearRedis();
        eventRepository.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        clearRedis();
        eventRepository.deleteAll();
    }

    @Test
    void shouldUseSameRedisConnectionForCacheAndTemplate() {
        String key = "ticketcraft:catalog:test:connection";

        redisTemplate.opsForValue().set(key, "ok");

        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("ok");

        assertThat(redisConnectionFactory.getConnection().ping()).isEqualTo("PONG");
    }

    @Test
    void shouldConnectToTestRedis() {
        assertThat(redisConnectionFactory.getConnection().ping()).isEqualTo("PONG");
    }

    @Test
    void shouldReturnCachedEventsOnSecondCall() {
        Event event = createEvent("Java Conference 2026", "TicketCraft Redis cache test",
                Instant.parse("2026-10-15T18:00:00Z"), "Tashkent IT Park");

        Event savedEvent = eventRepository.saveAndFlush(event);

        List<EventSummaryResponse> firstResult = eventCatalogService.getEvents();

        assertThat(firstResult).hasSize(1);

        assertThat(firstResult.getFirst().title()).isEqualTo("Java Conference 2026");

        assertThat(redisTemplate.hasKey(EVENTS_CACHE_KEY)).isTrue();

        /*
         * Изменяем PostgreSQL напрямую и намеренно не очищаем cache.
         *
         * Второй вызов должен вернуть ранее закешированное значение, а не новое
         * значение из PostgreSQL.
         */
        Event databaseEvent = eventRepository.findById(savedEvent.getId()).orElseThrow();

        databaseEvent.setTitle("CHANGED IN DATABASE");

        eventRepository.saveAndFlush(databaseEvent);

        List<EventSummaryResponse> secondResult = eventCatalogService.getEvents();

        assertThat(secondResult).hasSize(1);

        assertThat(secondResult.getFirst().title()).isEqualTo("Java Conference 2026");

        assertThat(secondResult).isEqualTo(firstResult);
    }

    @Test
    void shouldReadFreshEventsFromDatabaseAfterCacheEviction() {
        Event event = createEvent("Original title", "Cache eviction test", Instant.parse("2026-10-20T18:00:00Z"),
                "Main Hall");

        Event savedEvent = eventRepository.saveAndFlush(event);

        List<EventSummaryResponse> firstResult = eventCatalogService.getEvents();

        assertThat(firstResult).hasSize(1);

        assertThat(firstResult.getFirst().title()).isEqualTo("Original title");

        Event databaseEvent = eventRepository.findById(savedEvent.getId()).orElseThrow();

        databaseEvent.setTitle("Updated title");

        eventRepository.saveAndFlush(databaseEvent);

        /*
         * Пока cache существует, сервис должен вернуть старое значение.
         */
        List<EventSummaryResponse> cachedResult = eventCatalogService.getEvents();

        assertThat(cachedResult).hasSize(1);

        assertThat(cachedResult.getFirst().title()).isEqualTo("Original title");

        Boolean deleted = redisTemplate.delete(EVENTS_CACHE_KEY);

        assertThat(deleted).isTrue();

        assertThat(redisTemplate.hasKey(EVENTS_CACHE_KEY)).isFalse();

        List<EventSummaryResponse> resultAfterEviction = eventCatalogService.getEvents();

        assertThat(resultAfterEviction).hasSize(1);

        assertThat(resultAfterEviction.getFirst().title()).isEqualTo("Updated title");
    }

    @Test
    void shouldStoreEventsListInRedis() {
        Event event = createEvent("Redis Serialization Conference", "Serialization test",
                Instant.parse("2026-12-01T10:00:00Z"), "Redis Hall");

        Event savedEvent = eventRepository.saveAndFlush(event);

        List<EventSummaryResponse> response = eventCatalogService.getEvents();

        assertThat(response).hasSize(1);

        EventSummaryResponse eventResponse = response.getFirst();

        assertThat(eventResponse.id()).isEqualTo(savedEvent.getId());

        assertThat(eventResponse.title()).isEqualTo("Redis Serialization Conference");

        assertThat(redisTemplate.hasKey(EVENTS_CACHE_KEY)).isTrue();

        String cachedJson = redisTemplate.opsForValue().get(EVENTS_CACHE_KEY);

        assertThat(cachedJson).as("Redis must contain serialized events list").isNotNull()
                .contains("Redis Serialization Conference").contains("Serialization test").contains("Redis Hall")
                .contains(savedEvent.getId().toString());
    }

    private Event createEvent(String title, String description, Instant eventDate, String venue) {
        Event event = new Event();

        event.setTitle(title);
        event.setDescription(description);
        event.setEventDate(eventDate);
        event.setVenue(venue);

        return event;
    }

    private void clearRedis() {
        Set<String> keys = redisTemplate.keys("ticketcraft:catalog:*");

        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @TestConfiguration
    static class StrictCacheTestConfiguration {

        @Bean
        @Primary
        CacheErrorHandler cacheErrorHandler() {
            return new SimpleCacheErrorHandler();
        }
    }
}
