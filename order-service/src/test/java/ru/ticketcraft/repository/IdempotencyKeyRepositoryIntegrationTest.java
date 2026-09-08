package ru.ticketcraft.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.model.IdempotencyKey;
import ru.ticketcraft.model.IdempotencyStatus;

@Testcontainers
@DataJdbcTest
class IdempotencyKeyRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("order_db").withUsername("postgres").withPassword("postgres");

    private static final String KEY = "integration-key";
    private static final Long USER_ID = 100L;
    private static final String HASH = "hash-123";

    @Autowired
    private IdempotencyKeyRepository repository;

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void shouldCreateIdempotencyKeyOnlyOnce() {
        int firstInsert = repository.tryCreate(
                KEY,
                USER_ID,
                HASH
        );

        int secondInsert = repository.tryCreate(
                KEY,
                USER_ID,
                HASH
        );

        assertEquals(1, firstInsert);
        assertEquals(0, secondInsert);

        Optional<IdempotencyKey> saved = repository.findById(KEY);

        assertNotNull(saved);
        assertEquals(KEY, saved.orElseThrow().getIdempotencyKey());
        assertEquals(USER_ID, saved.orElseThrow().getUserId());
        assertEquals(HASH, saved.orElseThrow().getRequestHash());
        assertEquals(
                IdempotencyStatus.IN_PROGRESS,
                saved.orElseThrow().getStatus()
        );
    }

    @Test
    void shouldKeepOriginalDataWhenSameKeyIsInsertedAgain() {
        repository.tryCreate(
                KEY,
                USER_ID,
                "original-hash"
        );

        repository.tryCreate(
                KEY,
                999L,
                "different-hash"
        );

        IdempotencyKey saved = repository.findById(KEY)
                .orElseThrow();

        assertEquals(USER_ID, saved.getUserId());
        assertEquals("original-hash", saved.getRequestHash());
        assertEquals(
                IdempotencyStatus.IN_PROGRESS,
                saved.getStatus()
        );
    }

    @Test
    void shouldMarkKeyAsCompleted() {
        repository.tryCreate(
                KEY,
                USER_ID,
                HASH
        );

        int updated = repository.markCompleted(
                KEY,
                42L
        );

        assertEquals(1, updated);

        IdempotencyKey saved = repository.findById(KEY)
                .orElseThrow();

        assertEquals(
                IdempotencyStatus.COMPLETED,
                saved.getStatus()
        );
        assertEquals(42L, saved.getOrderId());
    }

    @Test
    void shouldNotCompleteAlreadyCompletedKeyTwice() {
        repository.tryCreate(
                KEY,
                USER_ID,
                HASH
        );

        assertEquals(
                1,
                repository.markCompleted(KEY, 42L)
        );

        assertEquals(
                0,
                repository.markCompleted(KEY, 999L)
        );

        IdempotencyKey saved = repository.findById(KEY)
                .orElseThrow();

        assertEquals(42L, saved.getOrderId());
        assertEquals(
                IdempotencyStatus.COMPLETED,
                saved.getStatus()
        );
    }
}
