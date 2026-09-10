package ru.ticketcraft.repository;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.model.Order;

public interface OrderRepository extends CrudRepository<Order, Long> {

    @Modifying
    @Query("""
            UPDATE orders
            SET status = :targetStatus
            WHERE id = :orderId
              AND status = :expectedStatus
            """)
    int updateStatusIfCurrent(@Param("orderId") Long orderId, @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus);

    default boolean transitionStatus(Long orderId, OrderState expectedStatus, OrderState targetStatus) {

        return updateStatusIfCurrent(orderId, expectedStatus.name(), targetStatus.name()) == 1;
    }
}