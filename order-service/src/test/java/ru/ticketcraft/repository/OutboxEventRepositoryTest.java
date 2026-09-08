package ru.ticketcraft.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.model.OutboxEvent;
import ru.ticketcraft.model.OutboxStatus;

@Testcontainers
@DataJdbcTest
class OutboxEventRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");

    private static final Instant NOW = Instant.parse("2099-01-01T10:01:00Z");

    private static final Instant LOCKED_AT = Instant.parse("2099-01-01T10:01:00Z");

    private static final Instant LOCK_EXPIRATION = Instant.parse("2099-01-01T10:00:00Z");

    private static final UUID EVENT_ID = UUID.randomUUID();

    private static final UUID FIRST_CLAIM_ID = UUID.randomUUID();

    private static final UUID SECOND_CLAIM_ID = UUID.randomUUID();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("order_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_events");

        repository.insert(EVENT_ID, "ORDER", "123", "OrderCreated", "{\"orderId\":123}", CREATED_AT);
    }

    @Test
    void shouldClaimPendingEvent() {
        int claimed = repository.claimPending(FIRST_CLAIM_ID, NOW, LOCK_EXPIRATION, LOCKED_AT, "publisher-1", 10);

        assertEquals(1, claimed);

        List<OutboxEvent> claimedEvents = repository.findClaimed(FIRST_CLAIM_ID);

        assertEquals(1, claimedEvents.size());

        OutboxEvent event = claimedEvents.getFirst();

        assertEquals(EVENT_ID, event.getId());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(FIRST_CLAIM_ID, event.getClaimId());
        assertEquals("publisher-1", event.getLockedBy());
        assertEquals(LOCKED_AT, event.getLockedAt());
        assertEquals(1, event.getAttempts());
    }

    @Test
    void shouldNotFindEventUsingDifferentClaimId() {
        int claimed = repository.claimPending(FIRST_CLAIM_ID, NOW, LOCK_EXPIRATION, LOCKED_AT, "publisher-1", 10);

        assertEquals(1, claimed);

        List<OutboxEvent> events = repository.findClaimed(SECOND_CLAIM_ID);

        assertTrue(events.isEmpty());
    }

    @Test
    void shouldMarkClaimedEventAsPublished() {
        int claimed = repository.claimPending(FIRST_CLAIM_ID, NOW, LOCK_EXPIRATION, LOCKED_AT, "publisher-1", 10);

        assertEquals(1, claimed);

        int published = repository.markPublished(EVENT_ID, FIRST_CLAIM_ID);

        assertEquals(1, published);

        List<OutboxEvent> claimedEvents = repository.findClaimed(FIRST_CLAIM_ID);

        assertTrue(claimedEvents.isEmpty());

        OutboxEvent event = repository.findById(EVENT_ID).orElseThrow();

        assertEquals(OutboxStatus.PUBLISHED, event.getStatus());
        assertEquals(null, event.getClaimId());
        assertEquals(null, event.getLockedAt());
        assertEquals(null, event.getLockedBy());
        assertNotEquals(null, event.getPublishedAt());
    }

    @Test
    void shouldNotMarkEventAsPublishedUsingStaleClaimId() {
        int firstClaimed = repository.claimPending(FIRST_CLAIM_ID, NOW, LOCK_EXPIRATION, LOCKED_AT, "publisher-1", 10);

        assertEquals(1, firstClaimed);

        Instant expiredLock = LOCKED_AT.minusSeconds(60);

        jdbcTemplate.update("""
                UPDATE outbox_events
                SET locked_at = ?
                WHERE id = ?
                """, Timestamp.from(expiredLock), EVENT_ID);

        Instant secondNow = NOW.plusSeconds(60);
        Instant secondLockExpiration = secondNow.minusSeconds(1);
        Instant secondLockedAt = LOCKED_AT.plusSeconds(60);

        int secondClaimed = repository.claimPending(SECOND_CLAIM_ID, secondNow, secondLockExpiration, secondLockedAt,
                "publisher-2", 10);

        assertEquals(1, secondClaimed);

        int stalePublished = repository.markPublished(EVENT_ID, FIRST_CLAIM_ID);

        assertEquals(0, stalePublished);

        int currentPublished = repository.markPublished(EVENT_ID, SECOND_CLAIM_ID);

        assertEquals(1, currentPublished);

        OutboxEvent event = repository.findById(EVENT_ID).orElseThrow();

        assertEquals(OutboxStatus.PUBLISHED, event.getStatus());
    }

    @Test
    void shouldReleaseClaimedEvent() {
        int claimed = repository.claimPending(FIRST_CLAIM_ID, NOW, LOCK_EXPIRATION, LOCKED_AT, "publisher-1", 10);

        assertEquals(1, claimed);

        Instant nextAttemptAt = NOW.plusSeconds(30);

        int released = repository.releaseClaim(EVENT_ID, FIRST_CLAIM_ID, nextAttemptAt);

        assertEquals(1, released);

        List<OutboxEvent> claimedEvents = repository.findClaimed(FIRST_CLAIM_ID);

        assertTrue(claimedEvents.isEmpty());

        OutboxEvent event = repository.findById(EVENT_ID).orElseThrow();

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(null, event.getClaimId());
        assertEquals(null, event.getLockedAt());
        assertEquals(null, event.getLockedBy());
        assertEquals(nextAttemptAt, event.getNextAttemptAt());
    }

    @Test
    void shouldNotReleaseEventUsingStaleClaimId() {
        int claimed = repository.claimPending(FIRST_CLAIM_ID, NOW, LOCK_EXPIRATION, LOCKED_AT, "publisher-1", 10);

        assertEquals(1, claimed);

        int released = repository.releaseClaim(EVENT_ID, SECOND_CLAIM_ID, NOW.plusSeconds(30));

        assertEquals(0, released);

        OutboxEvent event = repository.findById(EVENT_ID).orElseThrow();

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(FIRST_CLAIM_ID, event.getClaimId());
    }

    @Test
    void shouldNotClaimEventBeforeNextAttemptAt() {
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET next_attempt_at = ?
                WHERE id = ?
                """, Timestamp.from(NOW.plusSeconds(60)), EVENT_ID);

        int claimed = repository.claimPending(FIRST_CLAIM_ID, NOW, LOCK_EXPIRATION, LOCKED_AT, "publisher-1", 10);

        assertEquals(0, claimed);
    }

    @Test
    void shouldClaimEventWhenNextAttemptAtIsDue() {
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET next_attempt_at = ?
                WHERE id = ?
                """, Timestamp.from(NOW.minusSeconds(1)), EVENT_ID);

        int claimed = repository.claimPending(FIRST_CLAIM_ID, NOW, LOCK_EXPIRATION, LOCKED_AT, "publisher-1", 10);

        assertEquals(1, claimed);
    }

    @Test
    void shouldNotClaimAlreadyClaimedEventWithinLease() {
        int firstClaimed = repository.claimPending(FIRST_CLAIM_ID, NOW, LOCK_EXPIRATION, LOCKED_AT, "publisher-1", 10);

        assertEquals(1, firstClaimed);

        int secondClaimed = repository.claimPending(SECOND_CLAIM_ID, NOW, LOCK_EXPIRATION, LOCKED_AT.plusSeconds(10),
                "publisher-2", 10);

        assertEquals(0, secondClaimed);
    }

    @Test
    void shouldReclaimEventAfterLeaseExpiration() {
        int firstClaimed = repository.claimPending(FIRST_CLAIM_ID, NOW, LOCK_EXPIRATION, LOCKED_AT, "publisher-1", 10);

        assertEquals(1, firstClaimed);

        Instant expiredLock = LOCKED_AT.minusSeconds(60);

        jdbcTemplate.update("""
                UPDATE outbox_events
                SET locked_at = ?
                WHERE id = ?
                """, Timestamp.from(expiredLock), EVENT_ID);

        Instant secondNow = NOW.plusSeconds(60);
        Instant secondLockExpiration = secondNow.minusSeconds(60);

        int secondClaimed = repository.claimPending(SECOND_CLAIM_ID, secondNow, secondLockExpiration,
                LOCKED_AT.plusSeconds(60), "publisher-2", 10);

        assertEquals(1, secondClaimed);

        List<OutboxEvent> events = repository.findClaimed(SECOND_CLAIM_ID);

        assertEquals(1, events.size());

        OutboxEvent event = events.getFirst();

        assertEquals(SECOND_CLAIM_ID, event.getClaimId());
        assertEquals("publisher-2", event.getLockedBy());
        assertEquals(2, event.getAttempts());
    }

    @Test
    void shouldRespectClaimLimit() {
        UUID secondEventId = UUID.randomUUID();
        UUID thirdEventId = UUID.randomUUID();

        repository.insert(secondEventId, "ORDER", "124", "OrderCreated", "{\"orderId\":124}",
                CREATED_AT.plusSeconds(1));

        repository.insert(thirdEventId, "ORDER", "125", "OrderCreated", "{\"orderId\":125}", CREATED_AT.plusSeconds(2));

        int claimed = repository.claimPending(FIRST_CLAIM_ID, NOW, LOCK_EXPIRATION, LOCKED_AT, "publisher-1", 2);

        assertEquals(2, claimed);

        List<OutboxEvent> events = repository.findClaimed(FIRST_CLAIM_ID);

        assertEquals(2, events.size());
    }

}