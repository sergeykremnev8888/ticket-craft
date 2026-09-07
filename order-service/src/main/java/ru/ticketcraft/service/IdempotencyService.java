package ru.ticketcraft.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.exception.OrderConflictException;
import ru.ticketcraft.model.IdempotencyKey;
import ru.ticketcraft.model.IdempotencyStatus;
import ru.ticketcraft.repository.IdempotencyKeyRepository;

@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Проверяет idempotency key и регистрирует новый запрос.
     *
     * @return существующую запись, если запрос уже выполнялся; null, если запрос
     *         новый и его можно выполнять
     */
    @Transactional
    public IdempotencyKey checkAndRegister(String idempotencyKey, Long userId, String requestHash) {
        validateKey(idempotencyKey);

        int created = repository.tryCreate(idempotencyKey, userId, requestHash);

        IdempotencyKey existing = repository.findById(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency key was not found: " + idempotencyKey));

        if (created == 1) {
            return existing;
        }

        validateExistingRequest(existing, userId, requestHash);

        if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
            return existing;
        }

        throw new OrderConflictException("Request with this Idempotency-Key is already in progress");
    }

    /**
     * Помечает idempotency key как успешно завершённый.
     */
    @Transactional
    public void complete(String idempotencyKey, Long orderId) {
        IdempotencyKey idempotencyKeyEntity = repository.findById(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency key not found: " + idempotencyKey));

        idempotencyKeyEntity.setOrderId(orderId);
        idempotencyKeyEntity.setStatus(IdempotencyStatus.COMPLETED);

        repository.save(idempotencyKeyEntity);
    }

    private IdempotencyKey validateExistingRequest(IdempotencyKey existing, Long userId, String requestHash) {
        if (!existing.getUserId().equals(userId)) {
            throw new OrderConflictException("Idempotency key belongs to another user");
        }

        if (!existing.getRequestHash().equals(requestHash)) {
            throw new OrderConflictException("Idempotency key was already used with a different request");
        }

        if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            throw new OrderConflictException("Request with this idempotency key is already in progress");
        }

        return existing;
    }

    private void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new OrderConflictException("Idempotency-Key header is required");
        }

        if (idempotencyKey.length() > 128) {
            throw new OrderConflictException("Idempotency-Key must not exceed 128 characters");
        }
    }

}
