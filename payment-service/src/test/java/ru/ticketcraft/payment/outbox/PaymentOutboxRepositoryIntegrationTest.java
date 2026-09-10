package ru.ticketcraft.payment.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJdbcTest
@Import(PaymentOutboxRepository.class)
@Testcontainers
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentOutboxRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("payment_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM payment_outbox");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM payment_outbox");
    }

    @Test
    void shouldNotClaimSameOutboxRecordsByConcurrentWorkers() throws Exception {
        Instant createdAt = Instant.parse("2026-09-10T10:00:00Z");

        int recordsCount = 20;
        for (int index = 0; index < recordsCount; index++) {
            UUID id = UUID.randomUUID();

            boolean inserted = outboxRepository.insertIfAbsent(id, "payment-outbox-message-" + index, 1_000L + index,
                    PaymentOutboxEventType.PAYMENT_SUCCEEDED.name(), """
                            {
                              "messageId": "payment-message",
                              "orderId": 100,
                              "paymentId": "11111111-1111-1111-1111-111111111111",
                              "amount": 150.00,
                              "occurredAt": "2026-09-10T10:00:00Z"
                            }
                            """, createdAt.plusSeconds(index));

            assertThat(inserted).isTrue();
        }

        String workerOne = "payment-instance-1";
        String workerTwo = "payment-instance-2";

        Instant claimedAt = Instant.parse("2026-09-10T10:01:00Z");

        Instant claimExpiredBefore = claimedAt.minusSeconds(30);

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<List<PaymentOutboxRecord>> firstFuture = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();

                return outboxRepository.claimBatch(10, workerOne, claimedAt, claimExpiredBefore);
            });

            Future<List<PaymentOutboxRecord>> secondFuture = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();

                return outboxRepository.claimBatch(10, workerTwo, claimedAt, claimExpiredBefore);
            });

            readyLatch.await();

            startLatch.countDown();

            List<PaymentOutboxRecord> firstBatch = firstFuture.get();

            List<PaymentOutboxRecord> secondBatch = secondFuture.get();

            assertThat(firstBatch).hasSize(10);
            assertThat(secondBatch).hasSize(10);

            Set<UUID> firstIds = extractIds(firstBatch);
            Set<UUID> secondIds = extractIds(secondBatch);

            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);

            Set<UUID> allClaimedIds = new HashSet<>(firstIds);

            allClaimedIds.addAll(secondIds);

            assertThat(allClaimedIds).hasSize(recordsCount);

            assertThat(firstBatch).allSatisfy(record -> assertThat(record.claimedBy()).isEqualTo(workerOne));

            assertThat(secondBatch).allSatisfy(record -> assertThat(record.claimedBy()).isEqualTo(workerTwo));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReclaimOutboxRecordWhenClaimLeaseExpired() {
        UUID outboxId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        Instant createdAt = Instant.parse("2026-09-10T10:00:00Z");

        boolean inserted = outboxRepository.insertIfAbsent(outboxId, "payment:payment-id:succeeded", 100L,
                PaymentOutboxEventType.PAYMENT_SUCCEEDED.name(), """
                        {
                          "messageId": "payment:payment-id:succeeded",
                          "orderId": 100,
                          "paymentId": "22222222-2222-2222-2222-222222222222",
                          "amount": 150.00,
                          "occurredAt": "2026-09-10T10:00:00Z"
                        }
                        """, createdAt);

        assertThat(inserted).isTrue();

        Instant firstClaimAt = Instant.parse("2026-09-10T10:01:00Z");

        List<PaymentOutboxRecord> firstClaim = outboxRepository.claimBatch(1, "instance-1", firstClaimAt,
                firstClaimAt.minusSeconds(30));

        assertThat(firstClaim).hasSize(1);

        Instant secondClaimAt = firstClaimAt.plusSeconds(31);

        List<PaymentOutboxRecord> secondClaim = outboxRepository.claimBatch(1, "instance-2", secondClaimAt,
                secondClaimAt.minusSeconds(30));

        assertThat(secondClaim).hasSize(1);

        PaymentOutboxRecord reclaimedRecord = secondClaim.getFirst();

        assertThat(reclaimedRecord.id()).isEqualTo(outboxId);

        assertThat(reclaimedRecord.claimedBy()).isEqualTo("instance-2");
    }

    @Test
    void shouldNotClaimOutboxRecordWhenLeaseIsStillActive() {
        UUID outboxId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        Instant createdAt = Instant.parse("2026-09-10T10:00:00Z");

        boolean inserted = outboxRepository.insertIfAbsent(outboxId, "payment:payment-id:succeeded", 100L,
                PaymentOutboxEventType.PAYMENT_SUCCEEDED.name(), """
                        {
                          "messageId": "payment:payment-id:succeeded",
                          "orderId": 100,
                          "paymentId": "22222222-2222-2222-2222-222222222222",
                          "amount": 150.00,
                          "occurredAt": "2026-09-10T10:00:00Z"
                        }
                        """, createdAt);

        assertThat(inserted).isTrue();

        Instant firstClaimAt = Instant.parse("2026-09-10T10:01:00Z");

        List<PaymentOutboxRecord> firstClaim = outboxRepository.claimBatch(1, "instance-1", firstClaimAt,
                firstClaimAt.minusSeconds(30));

        assertThat(firstClaim).hasSize(1);

        Instant secondClaimAt = firstClaimAt.plusSeconds(10);

        List<PaymentOutboxRecord> secondClaim = outboxRepository.claimBatch(1, "instance-2", secondClaimAt,
                secondClaimAt.minusSeconds(30));

        assertThat(secondClaim).isEmpty();
    }

    private Set<UUID> extractIds(List<PaymentOutboxRecord> records) {
        Set<UUID> ids = new HashSet<>();

        for (PaymentOutboxRecord record : records) {
            ids.add(record.id());
        }

        return ids;
    }
}