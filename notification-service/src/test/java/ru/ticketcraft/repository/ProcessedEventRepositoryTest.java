package ru.ticketcraft.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@DataJdbcTest
@Import(ProcessedEventRepository.class)
class ProcessedEventRepositoryTest {

    private static final String MESSAGE_ID_1 = "message-001";
    private static final String MESSAGE_ID_2 = "message-002";

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("notification_db").withUsername("postgres").withPassword("postgres");

    @Autowired
    private ProcessedEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM processed_events");
    }

    @Test
    void shouldInsertNewEvent() {
        int inserted = repository.insertIfAbsent(MESSAGE_ID_1);

        assertEquals(1, inserted);

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM processed_events
                WHERE message_id = ?
                """, Integer.class, MESSAGE_ID_1);

        assertEquals(1, count);
    }

    @Test
    void shouldIgnoreDuplicateEvent() {
        int firstInsert = repository.insertIfAbsent(MESSAGE_ID_1);
        int secondInsert = repository.insertIfAbsent(MESSAGE_ID_1);

        assertEquals(1, firstInsert);
        assertEquals(0, secondInsert);

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM processed_events
                WHERE message_id = ?
                """, Integer.class, MESSAGE_ID_1);

        assertEquals(1, count);
    }

    @Test
    void shouldStoreDifferentEvents() {
        int firstInsert = repository.insertIfAbsent(MESSAGE_ID_1);
        int secondInsert = repository.insertIfAbsent(MESSAGE_ID_2);

        assertEquals(1, firstInsert);
        assertEquals(1, secondInsert);

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_events", Integer.class);

        assertEquals(2, count);
    }

    @Test
    void shouldInsertEventOnlyOnceUnderConcurrentAccess() throws Exception {
        int threadCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        try {
            List<Callable<Integer>> tasks = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                tasks.add(() -> {
                    readyLatch.countDown();
                    startLatch.await();
                    return repository.insertIfAbsent(MESSAGE_ID_1);
                });
            }

            List<Future<Integer>> futures = new ArrayList<>();

            for (Callable<Integer> task : tasks) {
                futures.add(executor.submit(task));
            }

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "Not all worker threads became ready");

            startLatch.countDown();

            int successfulInserts = 0;

            for (Future<Integer> future : futures) {
                successfulInserts += future.get();
            }

            assertEquals(1, successfulInserts);

            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM processed_events
                    WHERE message_id = ?
                    """, Integer.class, MESSAGE_ID_1);

            assertEquals(1, count);
        } finally {
            executor.shutdownNow();
        }
    }

}
