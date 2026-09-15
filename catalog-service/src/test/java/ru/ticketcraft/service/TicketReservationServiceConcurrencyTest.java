package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.EventRepository;
import ru.ticketcraft.repository.TicketRepository;

@Testcontainers
@SpringBootTest(properties = {
        "ticketcraft.outbox.publisher.enabled=false",
        "ticketcraft.rate-limit.enabled=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TicketReservationServiceConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 20;

    private static final Long USER_ID = 501L;

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("catalog_db")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Autowired
    private TicketReservationService reservationService;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executorService;

    @BeforeAll
    void setUpExecutor() {
        executorService = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
    }

    @BeforeEach
    void setUpDatabase() {
        cleanDatabase();
    }

    @AfterEach
    void cleanUpDatabase() {
        cleanDatabase();
    }

    @AfterAll
    void tearDownExecutor() throws InterruptedException {
        executorService.shutdown();

        if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
            executorService.shutdownNow();
        }
    }

    @Test
    void shouldReserveTicketForExactlyOneSagaWhenCommandsAreConcurrent()
            throws Exception {

        Ticket ticket = createAvailableTicket();

        UUID ticketId = ticket.getId();

        List<UUID> reservationIds = new ArrayList<>();
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            long orderId = 1000L + i;

            UUID reservationId = UUID.randomUUID();

            reservationIds.add(reservationId);

            ReserveTicketCommand command = new ReserveTicketCommand(
                    "saga:" + reservationId + ":reserve-ticket",
                    orderId,
                    reservationId,
                    ticketId,
                    USER_ID,
                    Instant.now()
            );

            tasks.add(() -> {
                reservationService.processReserveTicketCommand(command);
                return null;
            });
        }

        /*
         * Все saga одновременно пытаются зарезервировать один AVAILABLE ticket.
         *
         * Business conflict не должен выбрасываться наружу exception-ом.
         * Каждый проигравший command должен завершиться
         * TicketReservationFailed event в outbox.
         */
        List<Future<Void>> futures = executorService.invokeAll(tasks);

        for (Future<Void> future : futures) {
            /*
             * get() нужен не ради результата.
             *
             * Он гарантирует, что все задачи завершены, и пробрасывает
             * неожиданные infrastructure/programming exceptions.
             */
            future.get();
        }

        Ticket reservedTicket = ticketRepository.findById(ticketId)
                .orElseThrow();

        assertThat(reservedTicket.getStatus())
                .isEqualTo(TicketStatus.RESERVED);

        assertThat(reservedTicket.getReservationId())
                .isNotNull()
                .isIn(reservationIds);

        assertThat(reservedTicket.getReservedUntil())
                .isNotNull()
                .isAfter(Instant.now());

        /*
         * Только одна saga должна выиграть atomic reservation.
         */
        Integer successEvents = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE event_type = 'TicketReserved'
                """,
                Integer.class
        );

        assertThat(successEvents)
                .as("Exactly one concurrent saga must reserve the ticket")
                .isEqualTo(1);

        /*
         * Все остальные saga должны получить ожидаемый business failure.
         */
        Integer failureEvents = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE event_type = 'TicketReservationFailed'
                """,
                Integer.class
        );

        assertThat(failureEvents)
                .as("All losing sagas must produce reservation failure events")
                .isEqualTo(CONCURRENT_REQUESTS - 1);

        /*
         * Каждая входящая команда должна породить ровно один result event:
         *
         * 1 success + 19 failures = 20.
         */
        Integer totalResultEvents = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE topic = 'ticket-reservation-results'
                """,
                Integer.class
        );

        assertThat(totalResultEvents)
                .isEqualTo(CONCURRENT_REQUESTS);

        /*
         * Проверяем, что failure действительно означает ownership conflict,
         * а не какой-либо другой результат.
         */
        Integer ownershipConflictEvents = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE event_type = 'TicketReservationFailed'
                  AND payload LIKE '%"reason":"TICKET_ALREADY_RESERVED"%'
                """,
                Integer.class
        );

        assertThat(ownershipConflictEvents)
                .isEqualTo(CONCURRENT_REQUESTS - 1);
    }

    private Ticket createAvailableTicket() {
        Event event = new Event();

        event.setTitle("Concurrency test event");
        event.setDescription(
                "Event for ticket reservation concurrency test"
        );
        event.setEventDate(
                Instant.now().plusSeconds(3600)
        );
        event.setVenue("Test venue");

        Event savedEvent = eventRepository.save(event);

        Ticket ticket = new Ticket();

        ticket.setEvent(savedEvent);
        ticket.setSeatNumber("A-1");
        ticket.setPrice(new BigDecimal("100.00"));
        ticket.setStatus(TicketStatus.AVAILABLE);

        return ticketRepository.save(ticket);
    }

    private void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM tickets");
        jdbcTemplate.update("DELETE FROM events");
    }
}
