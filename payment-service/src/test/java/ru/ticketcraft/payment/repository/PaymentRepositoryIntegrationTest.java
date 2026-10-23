package ru.ticketcraft.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.payment.model.Payment;
import ru.ticketcraft.payment.model.PaymentStatus;

@DataJdbcTest
@Import(PaymentRepository.class)
@Testcontainers
@ActiveProfiles("test")
class PaymentRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("payment_db")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldInsertAndFindPayment() {
        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        Instant now = Instant.parse("2026-09-09T10:00:00Z");

        Payment payment = new Payment(paymentId, 100L, 200L, new BigDecimal("150.00"), PaymentStatus.PENDING,
                "message-1", now, now);

        boolean inserted = paymentRepository.insertIfAbsent(payment);

        assertThat(inserted).isTrue();

        Optional<Payment> result = paymentRepository.findByOrderId(100L);

        assertThat(result).isPresent();

        assertThat(result.get().getId()).isEqualTo(paymentId);

        assertThat(result.get().getStatus()).isEqualTo(PaymentStatus.PENDING);

        assertThat(result.get().getAmount()).isEqualByComparingTo("150.00");
    }

    @Test
    void shouldIgnoreDuplicatePaymentForSameOrder() {
        UUID paymentId1 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID paymentId2 = UUID.fromString("33333333-3333-3333-3333-333333333333");

        Instant now = Instant.parse("2026-09-09T10:00:00Z");

        Payment firstPayment = new Payment(paymentId1, 200L, 300L, new BigDecimal("150.00"), PaymentStatus.PENDING,
                "message-2", now, now);
        Payment secondPayment = new Payment(paymentId2, 200L, 300L, new BigDecimal("150.00"), PaymentStatus.PENDING,
                "message-3", now, now);

        assertThat(paymentRepository.insertIfAbsent(firstPayment)).isTrue();
        assertThat(paymentRepository.insertIfAbsent(secondPayment)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = { "0.00", "-0.01" })
    void shouldRejectNonPositiveAmountAtDatabaseLevel(String value) {
        UUID paymentId = UUID.randomUUID();
        String messageId = "invalid-amount-" + paymentId;
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update("""
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
                """,
                 paymentId,
                 987654321L,
                 10L,
                 new BigDecimal(value),
                 "PENDING",
                 messageId,
                 now,
                 now)).isInstanceOf(DataIntegrityViolationException.class)
                      .hasStackTraceContaining("chk_payments_amount_positive");
    }
}