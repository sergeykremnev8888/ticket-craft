package ru.ticketcraft.repository;

import java.time.Instant;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import ru.ticketcraft.model.IdempotencyKey;

@Repository
public interface IdempotencyKeyRepository extends CrudRepository<IdempotencyKey, String> {

    @Modifying
    @Query("""
        INSERT INTO idempotency_keys (
            idempotency_key,
            user_id,
            request_hash,
            status,
            created_at
        )
        VALUES (
            :idempotencyKey,
            :userId,
            :requestHash,
            'IN_PROGRESS',
            CURRENT_TIMESTAMP
        )
        ON CONFLICT (idempotency_key) DO NOTHING
        """)
    int tryCreate(
            @Param("idempotencyKey") String idempotencyKey,
            @Param("userId") Long userId,
            @Param("requestHash") String requestHash
    );

    @Modifying
    @Query("""
        UPDATE idempotency_keys
        SET updated_at = CURRENT_TIMESTAMP
        WHERE idempotency_key = :idempotencyKey
          AND status = 'IN_PROGRESS'
          AND updated_at < :staleBefore
        """)
    int recoverStale(
        @Param("idempotencyKey") String idempotencyKey,
        @Param("staleBefore") Instant staleBefore
    );

    @Modifying
    @Query("""
        UPDATE idempotency_keys
        SET status = 'COMPLETED',
            order_id = :orderId,
            updated_at = CURRENT_TIMESTAMP
        WHERE idempotency_key = :idempotencyKey
          AND status = 'IN_PROGRESS'
        """)
    int markCompleted(
        @Param("idempotencyKey") String idempotencyKey,
        @Param("orderId") Long orderId
    );
}
