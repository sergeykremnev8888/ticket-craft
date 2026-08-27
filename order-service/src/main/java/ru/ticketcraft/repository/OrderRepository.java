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
	 * Важнейший метод для Highload секции на собеседовании. Мы запрашиваем статус
	 * билета из базы данных catalog_db (или нашей текущей схемы) и жестко блокируем
	 * строку с помощью FOR UPDATE. Если прилетит второй параллельный запрос на
	 * покупку этого же билета, PostgreSQL заставит его ждать, пока текущая
	 * транзакция не завершится (commit/rollback).
	 */
	@Query("SELECT is_available FROM tickets WHERE id = :ticketId FOR UPDATE")
	Boolean checkAvailabilityAndLock(@Param("ticketId") Long ticketId);

	/**
	 * Прямое обновление статуса билета через нативный SQL
	 */
	@Modifying
	@Query("UPDATE tickets SET is_available = :available WHERE id = :ticketId")
	void updateTicketStatus(@Param("ticketId") Long ticketId, @Param("available") boolean available);
}
