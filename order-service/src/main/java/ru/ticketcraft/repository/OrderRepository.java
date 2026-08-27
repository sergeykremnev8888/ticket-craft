package ru.ticketcraft.repository;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import ru.ticketcraft.model.Order;

@Repository
public interface OrderRepository extends CrudRepository<Order, Long> {

	/**
	 * Прямое обновление статуса билета через нативный SQL
	 */
	@Modifying
	@Query("UPDATE tickets SET is_available = :available WHERE id = :ticketId")
	void updateTicketStatus(@Param("ticketId") Long ticketId, @Param("available") boolean available);
}
