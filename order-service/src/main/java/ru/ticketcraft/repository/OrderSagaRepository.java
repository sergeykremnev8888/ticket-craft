package ru.ticketcraft.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import ru.ticketcraft.saga.OrderSaga;

public interface OrderSagaRepository extends CrudRepository<OrderSaga, UUID> {

    Optional<OrderSaga> findByOrderId(Long orderId);

    @Modifying
    @Query("""
            INSERT INTO order_sagas (
                id,
                order_id,
                status,
                created_at,
                updated_at
            )
            VALUES (
                :id,
                :orderId,
                :status,
                :createdAt,
                :updatedAt
            )
            ON CONFLICT (order_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("id") UUID id, @Param("orderId") Long orderId, @Param("status") String status,
            @Param("createdAt") Instant createdAt, @Param("updatedAt") Instant updatedAt);

    @Modifying
    @Query("""
            UPDATE order_sagas
            SET status = :targetStatus,
                updated_at = :updatedAt
            WHERE order_id = :orderId
              AND status = :expectedStatus
            """)
    int transition(@Param("orderId") Long orderId, @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus, @Param("updatedAt") Instant updatedAt);
}