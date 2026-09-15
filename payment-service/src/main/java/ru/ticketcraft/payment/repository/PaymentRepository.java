package ru.ticketcraft.payment.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import ru.ticketcraft.payment.model.Payment;
import ru.ticketcraft.payment.model.PaymentStatus;

@Repository
public class PaymentRepository {

    private final JdbcTemplate jdbcTemplate;

    public PaymentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertIfAbsent(Payment payment) {

        int affectedRows = jdbcTemplate.update("""
                INSERT INTO payments (
                    id,
                    order_id,
                    user_id,
                    amount,
                    status,
                    message_id,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_id) DO NOTHING
                """, payment.getId(), payment.getOrderId(), payment.getUserId(), payment.getAmount(),
                payment.getStatus().name(), payment.getMessageId(), Timestamp.from(payment.getCreatedAt()),
                Timestamp.from(payment.getUpdatedAt()));

        return affectedRows == 1;
    }

    public Optional<Payment> findByOrderId(Long orderId) {

        return jdbcTemplate.query("""
                SELECT
                    id,
                    order_id,
                    user_id,
                    amount,
                    status,
                    message_id,
                    created_at,
                    updated_at
                FROM payments
                WHERE order_id = ?
                """, ps -> ps.setLong(1, orderId), rs -> {

            if (!rs.next()) {
                return Optional.empty();
            }

            return Optional.of(mapPayment(rs));
        });
    }

    public Optional<Payment> findById(UUID paymentId) {

        return jdbcTemplate.query("""
                SELECT
                    id,
                    order_id,
                    user_id,
                    amount,
                    status,
                    message_id,
                    created_at,
                    updated_at
                FROM payments
                WHERE id = ?
                """, ps -> ps.setObject(1, paymentId), rs -> {

            if (!rs.next()) {
                return Optional.empty();
            }

            return Optional.of(mapPayment(rs));
        });
    }

    public int updateStatus(UUID paymentId, PaymentStatus expectedStatus, PaymentStatus targetStatus,
            Instant updatedAt) {

        return jdbcTemplate.update("""
                UPDATE payments
                SET status = ?,
                    updated_at = ?
                WHERE id = ?
                  AND status = ?
                """, targetStatus.name(), Timestamp.from(updatedAt), paymentId, expectedStatus.name());
    }

    private Payment mapPayment(java.sql.ResultSet rs) throws java.sql.SQLException {

        return new Payment(rs.getObject("id", UUID.class), rs.getLong("order_id"), rs.getLong("user_id"),
                rs.getBigDecimal("amount"), PaymentStatus.valueOf(rs.getString("status")), rs.getString("message_id"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}