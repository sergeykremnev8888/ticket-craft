package ru.ticketcraft.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@DataJdbcTest
class IdempotencyKeyRepositoryConcurrencyTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("order_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void shouldAllowOnlyOneRequestToCreateSameIdempotencyKey()
            throws Exception {

        String key = "concurrent-key";
        Long userId = 100L;
        String hash = "hash-123";

        int requestCount = 20;

        ExecutorService executor =
                Executors.newFixedThreadPool(requestCount);

        try {
            List<Callable<Integer>> tasks = new ArrayList<>();

            for (int i = 0; i < requestCount; i++) {
                tasks.add(() ->
                        transactionTemplate.execute(status ->
                                jdbcTemplate.update("""
                                    INSERT INTO idempotency_keys (
                                        idempotency_key,
                                        user_id,
                                        request_hash,
                                        status,
                                        created_at
                                    )
                                    VALUES (?, ?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP)
                                    ON CONFLICT (idempotency_key) DO NOTHING
                                    """,
                                key,
                                userId,
                                hash
                        )
                ));
            }

            List<Future<Integer>> futures =
                    executor.invokeAll(tasks);

            long successfulInserts = 0;

            for (Future<Integer> future : futures) {
                if (future.get() == 1) {
                    successfulInserts++;
                }
            }

            assertEquals(
                    1,
                    successfulInserts,
                    "Exactly one concurrent request must create the idempotency key"
            );

            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM idempotency_keys
                    WHERE idempotency_key = ?
                    """,
                    Integer.class,
                    key
            );

            assertEquals(1, count);
        } finally {
            executor.shutdownNow();
        }
    }
}
