package ru.ticketcraft.repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.model.Order;

public interface OrderRepository extends CrudRepository<Order, Long> {

    Optional<Order> findByIdAndUserId(Long id, Long userId);

    @Modifying
    @Query("""
            UPDATE orders
            SET status = :targetStatus
            WHERE id = :orderId
              AND status = :expectedStatus
            """)
    int updateStatusIfCurrent(@Param("orderId") Long orderId, @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus);

    @Modifying
    @Query("""
            UPDATE orders
            SET event_id = :eventId,
                total_price = :totalPrice
            WHERE id = :orderId
            """)
    int applyAuthoritativeReservationDetails(
            @Param("orderId") Long orderId,
            @Param("eventId") UUID eventId,
            @Param("totalPrice") BigDecimal totalPrice);

    default boolean transitionStatus(Long orderId, OrderState expectedStatus, OrderState targetStatus) {

        return updateStatusIfCurrent(orderId, expectedStatus.name(), targetStatus.name()) == 1;
    }
}