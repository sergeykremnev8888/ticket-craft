package ru.ticketcraft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.ticketcraft.exception.OrderConflictException;
import ru.ticketcraft.model.IdempotencyKey;
import ru.ticketcraft.model.IdempotencyStatus;
import ru.ticketcraft.repository.IdempotencyKeyRepository;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final String IDEMPOTENCY_KEY = "test-key";
    private static final Long USER_ID = 100L;
    private static final String REQUEST_HASH = "hash-123";

    @Mock
    private IdempotencyKeyRepository repository;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository);
    }

    @Test
    void shouldRegisterNewIdempotencyKey() {
        IdempotencyKey key = idempotencyKey(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH, null,
                IdempotencyStatus.IN_PROGRESS);

        when(repository.tryCreate(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH)).thenReturn(1);

        when(repository.findById(IDEMPOTENCY_KEY)).thenReturn(Optional.of(key));

        IdempotencyKey result = service.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH);

        assertNotNull(result);
        assertEquals(IDEMPOTENCY_KEY, result.getIdempotencyKey());
        assertEquals(USER_ID, result.getUserId());
        assertEquals(REQUEST_HASH, result.getRequestHash());
        assertEquals(IdempotencyStatus.IN_PROGRESS, result.getStatus());

        verify(repository).tryCreate(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH);
    }

    @Test
    void shouldReturnCompletedKeyForRepeatedRequest() {
        Long orderId = 42L;

        IdempotencyKey key = idempotencyKey(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH, orderId,
                IdempotencyStatus.COMPLETED);

        when(repository.tryCreate(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH)).thenReturn(0);

        when(repository.findById(IDEMPOTENCY_KEY)).thenReturn(Optional.of(key));

        IdempotencyKey result = service.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH);

        assertEquals(IdempotencyStatus.COMPLETED, result.getStatus());
        assertEquals(orderId, result.getOrderId());

        verify(repository).tryCreate(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH);
    }

    @Test
    void shouldRejectRequestWhenKeyIsAlreadyInProgress() {
        IdempotencyKey key = idempotencyKey(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH, null,
                IdempotencyStatus.IN_PROGRESS);

        when(repository.tryCreate(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH)).thenReturn(0);

        when(repository.findById(IDEMPOTENCY_KEY)).thenReturn(Optional.of(key));

        assertThrows(OrderConflictException.class,
                () -> service.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH));
    }

    @Test
    void shouldRejectSameKeyForAnotherUser() {
        IdempotencyKey key = idempotencyKey(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH, 42L, IdempotencyStatus.COMPLETED);

        when(repository.tryCreate(IDEMPOTENCY_KEY, 999L, REQUEST_HASH)).thenReturn(0);

        when(repository.findById(IDEMPOTENCY_KEY)).thenReturn(Optional.of(key));

        OrderConflictException exception = assertThrows(OrderConflictException.class,
                () -> service.checkAndRegister(IDEMPOTENCY_KEY, 999L, REQUEST_HASH));

        assertEquals("Idempotency key belongs to another user", exception.getMessage());
    }

    @Test
    void shouldRejectSameKeyForDifferentRequest() {
        IdempotencyKey key = idempotencyKey(IDEMPOTENCY_KEY, USER_ID, "original-hash", 42L,
                IdempotencyStatus.COMPLETED);

        when(repository.tryCreate(IDEMPOTENCY_KEY, USER_ID, "different-hash")).thenReturn(0);

        when(repository.findById(IDEMPOTENCY_KEY)).thenReturn(Optional.of(key));

        OrderConflictException exception = assertThrows(OrderConflictException.class,
                () -> service.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, "different-hash"));

        assertEquals("Idempotency key was already used with a different request", exception.getMessage());
    }

    @Test
    void shouldRejectNullIdempotencyKey() {
        assertThrows(OrderConflictException.class, () -> service.checkAndRegister(null, USER_ID, REQUEST_HASH));

        verify(repository, never()).tryCreate(any(), any(), any());
    }

    @Test
    void shouldRejectBlankIdempotencyKey() {
        assertThrows(OrderConflictException.class, () -> service.checkAndRegister("   ", USER_ID, REQUEST_HASH));

        verify(repository, never()).tryCreate(any(), any(), any());
    }

    @Test
    void shouldRejectTooLongIdempotencyKey() {
        String key = "a".repeat(129);

        assertThrows(OrderConflictException.class, () -> service.checkAndRegister(key, USER_ID, REQUEST_HASH));

        verify(repository, never()).tryCreate(any(), any(), any());
    }

    @Test
    void shouldAcceptIdempotencyKeyWithMaximumLength() {
        String key = "a".repeat(128);

        IdempotencyKey entity = idempotencyKey(key, USER_ID, REQUEST_HASH, null, IdempotencyStatus.IN_PROGRESS);

        when(repository.tryCreate(key, USER_ID, REQUEST_HASH)).thenReturn(1);

        when(repository.findById(key)).thenReturn(Optional.of(entity));

        IdempotencyKey result = service.checkAndRegister(key, USER_ID, REQUEST_HASH);

        assertEquals(key, result.getIdempotencyKey());
    }

    @Test
    void shouldThrowWhenCreatedKeyCannotBeFound() {
        when(repository.tryCreate(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH)).thenReturn(1);

        when(repository.findById(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service.checkAndRegister(IDEMPOTENCY_KEY, USER_ID, REQUEST_HASH));
    }

    @Test
    void shouldCompleteIdempotencyKey() {
        when(repository.markCompleted(IDEMPOTENCY_KEY, 42L)).thenReturn(1);

        service.complete(IDEMPOTENCY_KEY, 42L);

        verify(repository).markCompleted(IDEMPOTENCY_KEY, 42L);
    }

    @Test
    void shouldThrowWhenCompletingUnknownKey() {
        when(repository.markCompleted(IDEMPOTENCY_KEY, 42L)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.complete(IDEMPOTENCY_KEY, 42L));

        verify(repository).markCompleted(IDEMPOTENCY_KEY, 42L);
    }

    private IdempotencyKey idempotencyKey(String key, Long userId, String requestHash, Long orderId,
            IdempotencyStatus status) {
        Instant now = Instant.now();

        return new IdempotencyKey(key, userId, requestHash, orderId, status, now);
    }
}
