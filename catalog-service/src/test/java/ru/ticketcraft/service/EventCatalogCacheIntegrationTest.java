package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import ru.ticketcraft.config.CatalogCacheNames;
import ru.ticketcraft.dto.EventSummaryResponse;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.repository.EventRepository;
import ru.ticketcraft.support.CatalogTestContainersConfiguration;

@SpringBootTest(properties = { "ticketcraft.outbox.publisher.enabled=false", "catalog.cache.events-ttl=5m",
        "ticketcraft.rate-limit.enabled=false" })
@Import(CatalogTestContainersConfiguration.class)
class EventCatalogCacheIntegrationTest {

    private static final String EVENTS_CACHE_KEY = "ticketcraft:catalog:events::all";

    private static final String EVENT_BY_ID_CACHE_KEY_PREFIX = "ticketcraft:catalog:event-by-id::";

    @Autowired
    private EventCatalogService eventCatalogService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private CacheManager cacheManager;

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

        System.out.println("RedisConnectionFactory = " + redisConnectionFactory);
    }

    @Test
    void shouldReturnCachedEventsOnSecondCall() {

        // Given
        Event event = createEvent("Java Conference 2026", "TicketCraft Redis cache test",
                Instant.parse("2026-10-15T18:00:00Z"), "Tashkent IT Park");

        Event savedEvent = eventRepository.saveAndFlush(event);

        // First call -> PostgreSQL -> Redis
        List<EventSummaryResponse> firstResult = eventCatalogService.getEvents();

        assertThat(firstResult).hasSize(1);

        assertThat(firstResult.getFirst().title()).isEqualTo("Java Conference 2026");

        assertThat(redisTemplate.hasKey(EVENTS_CACHE_KEY)).isTrue();

        /*
         * Меняем данные напрямую в PostgreSQL.
         *
         * Важно: cache не очищаем.
         */
        Event databaseEvent = eventRepository.findById(savedEvent.getId()).orElseThrow();

        databaseEvent.setTitle("CHANGED IN DATABASE");

        eventRepository.saveAndFlush(databaseEvent);

        // When
        List<EventSummaryResponse> secondResult = eventCatalogService.getEvents();

        // Then
        /*
         * Если второй вызов пошёл в PostgreSQL, мы увидим "CHANGED IN DATABASE".
         *
         * Если второй вызов пришёл из Redis, останется старое значение.
         */
        assertThat(secondResult).hasSize(1);

        assertThat(secondResult.getFirst().title()).isEqualTo("Java Conference 2026");

        assertThat(secondResult).isEqualTo(firstResult);
    }

    @Test
    void shouldReadFreshEventsFromDatabaseAfterCacheEviction() {

        // Given
        Event event = createEvent("Original title", "Cache eviction test", Instant.parse("2026-10-20T18:00:00Z"),
                "Main Hall");

        Event savedEvent = eventRepository.saveAndFlush(event);

        List<EventSummaryResponse> firstResult = eventCatalogService.getEvents();

        assertThat(firstResult.getFirst().title()).isEqualTo("Original title");

        Event databaseEvent = eventRepository.findById(savedEvent.getId()).orElseThrow();

        databaseEvent.setTitle("Updated title");

        eventRepository.saveAndFlush(databaseEvent);

        /*
         * Пока cache жив, получаем старое значение.
         */
        List<EventSummaryResponse> cachedResult = eventCatalogService.getEvents();

        assertThat(cachedResult.getFirst().title()).isEqualTo("Original title");

        // When
        Boolean deleted = redisTemplate.delete(EVENTS_CACHE_KEY);

        assertThat(deleted).isTrue();

        assertThat(redisTemplate.hasKey(EVENTS_CACHE_KEY)).isFalse();

        List<EventSummaryResponse> resultAfterEviction = eventCatalogService.getEvents();

        // Then
        assertThat(resultAfterEviction.getFirst().title()).isEqualTo("Updated title");
    }

    @Test
    void shouldReturnCachedEventByIdOnSecondCall() {

        // Given
        Event event = createEvent("Spring Conference 2026", "Single event cache test",
                Instant.parse("2026-11-20T15:00:00Z"), "Conference Hall");

        Event savedEvent = eventRepository.saveAndFlush(event);

        UUID eventId = savedEvent.getId();

        // First call -> PostgreSQL -> Redis
        EventSummaryResponse firstResult = eventCatalogService.getEvent(eventId);

        assertThat(firstResult.title()).isEqualTo("Spring Conference 2026");

        String redisKey = EVENT_BY_ID_CACHE_KEY_PREFIX + eventId;

        assertThat(redisTemplate.hasKey(redisKey)).isTrue();

        /*
         * Меняем только PostgreSQL. Redis cache не трогаем.
         */
        Event databaseEvent = eventRepository.findById(eventId).orElseThrow();

        databaseEvent.setTitle("CHANGED IN DATABASE");

        eventRepository.saveAndFlush(databaseEvent);

        // When
        EventSummaryResponse secondResult = eventCatalogService.getEvent(eventId);

        // Then
        assertThat(secondResult.title()).isEqualTo("Spring Conference 2026");

        assertThat(secondResult).isEqualTo(firstResult);
    }

    @Test
    void shouldStoreNonEmptyEventSummaryResponseInRedis() {

        // Given
        Event event = createEvent("Redis Serialization Conference", "Serialization test",
                Instant.parse("2026-12-01T10:00:00Z"), "Redis Hall");

        Event savedEvent = eventRepository.saveAndFlush(event);

        UUID eventId = savedEvent.getId();

        Cache cache = cacheManager.getCache(CatalogCacheNames.EVENT_BY_ID);

        assertThat(cache).as("event-by-id cache must be configured").isNotNull();

        /*
         * До вызова service cache должен быть пустым.
         */
        assertThat(cache.get(eventId)).as("Cache must be empty before first service call").isNull();

        // When
        EventSummaryResponse response = eventCatalogService.getEvent(eventId);

        // Then
        assertThat(response.id()).isEqualTo(eventId);

        assertThat(response.title()).isEqualTo("Redis Serialization Conference");

        /*
         * Сначала проверяем Spring Cache abstraction.
         *
         * Если это падает — проблема именно в @Cacheable / cache PUT. Если проходит, но
         * physical key ниже отсутствует — CacheManager и StringRedisTemplate подключены
         * к разным Redis.
         */
        Cache.ValueWrapper cached = cache.get(eventId);

        assertThat(cached).as("Spring Cache must contain event %s immediately after @Cacheable call", eventId)
                .isNotNull();

        assertThat(cached.get()).isInstanceOf(EventSummaryResponse.class);

        EventSummaryResponse cachedResponse = (EventSummaryResponse) cached.get();

        assertThat(cachedResponse).isEqualTo(response);

        String expectedRedisKey = EVENT_BY_ID_CACHE_KEY_PREFIX + eventId;

        /*
         * Теперь проверяем фактическое Redis keyspace.
         */
        Set<String> physicalKeys = redisTemplate.keys("ticketcraft:catalog:*");

        assertThat(physicalKeys).as("Physical catalog Redis keys").isNotNull();

        assertThat(physicalKeys)
                .as("Redis must contain key %s. Actual catalog keys: %s", expectedRedisKey, physicalKeys)
                .contains(expectedRedisKey);

        String cachedJson = redisTemplate.opsForValue().get(expectedRedisKey);

        assertThat(cachedJson).isNotNull().contains("ru.ticketcraft.dto.EventSummaryResponse")
                .contains(eventId.toString()).contains("Redis Serialization Conference").contains("Serialization test")
                .contains("Redis Hall");
    }

    @Test
    void shouldUseIndependentCachesForEventsListAndEventById() {

        // Given
        Event event = createEvent("Highload Conference 2026", "Independent cache test",
                Instant.parse("2026-12-10T10:00:00Z"), "Highload Hall");

        Event savedEvent = eventRepository.saveAndFlush(event);

        UUID eventId = savedEvent.getId();

        // When
        eventCatalogService.getEvents();
        eventCatalogService.getEvent(eventId);

        // Then
        assertThat(redisTemplate.hasKey(EVENTS_CACHE_KEY)).isTrue();

        assertThat(redisTemplate.hasKey(EVENT_BY_ID_CACHE_KEY_PREFIX + eventId)).isTrue();
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

}