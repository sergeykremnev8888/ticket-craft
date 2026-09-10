package ru.ticketcraft.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.model.OutboxEvent;
import ru.ticketcraft.repository.OutboxEventRepository;

@Service
public class OutboxClaimService {

    private final OutboxEventRepository repository;

    public OutboxClaimService(OutboxEventRepository repository) {

        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int claimPending(UUID claimId, Instant now, Instant lockExpiration, Instant lockedAt, String lockedBy,
            int limit) {

        return repository.claimPending(claimId, now, lockExpiration, lockedAt, lockedBy, limit);
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> findClaimed(UUID claimId) {

        return repository.findClaimed(claimId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(UUID eventId, UUID claimId) {

        int updated = repository.markPublished(eventId, claimId, Instant.now());

        return updated == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean releaseClaim(UUID eventId, UUID claimId, Instant nextAttemptAt) {

        int updated = repository.releaseClaim(eventId, claimId, nextAttemptAt);

        return updated == 1;
    }
}