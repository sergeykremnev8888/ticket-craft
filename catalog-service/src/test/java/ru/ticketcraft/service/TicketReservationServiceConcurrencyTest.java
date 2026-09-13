package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.exception.TicketAlreadyReservedException;
import ru.ticketcraft.model.Event;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.EventRepository;
import ru.ticketcraft.repository.TicketRepository;

@Testcontainers
@SpringBootTest(properties = {
        "ticketcraft.rate-limit.enabled=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TicketReservationServiceConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 20;

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Testcontainers manages the container lifecycle.
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

    private ExecutorService executorService;

    @BeforeAll
    void setUp() {
        executorService = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
    }

    @AfterAll
    void tearDown() throws InterruptedException {
        executorService.shutdown();

        if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
            executorService.shutdownNow();
        }
    }

    @Test
    void shouldReserveTicketOnlyOnceWhenRequestsAreConcurrent()
            throws Exception {

        // Given
        Ticket ticket = createAvailableTicket();
        UUID ticketId = ticket.getId();

        List<Callable<UUID>> tasks = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            tasks.add(() -> reservationService.reserveTicket(ticketId));
        }

        // When
        List<Future<UUID>> futures = executorService.invokeAll(tasks);

        // Then
        int successfulReservations = 0;
        int rejectedReservations = 0;

        List<UUID> reservationIds = new ArrayList<>();

        for (Future<UUID> future : futures) {
            try {
                UUID reservationId = future.get();

                successfulReservations++;
                reservationIds.add(reservationId);

            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof TicketAlreadyReservedException) {
                    rejectedReservations++;
                } else {
                    throw ex;
                }
            }
        }

        assertThat(successfulReservations)
                .as("Exactly one concurrent request must reserve the ticket")
                .isEqualTo(1);

        assertThat(rejectedReservations)
                .as("All other concurrent requests must be rejected")
                .isEqualTo(CONCURRENT_REQUESTS - 1);

        assertThat(reservationIds)
                .as("Exactly one reservation ID must be generated")
                .hasSize(1)
                .doesNotContainNull();

        UUID reservationId = reservationIds.getFirst();

        // Verify persisted state
        Ticket reservedTicket = ticketRepository.findById(ticketId)
                .orElseThrow();

        assertThat(reservedTicket.getStatus())
                .isEqualTo(TicketStatus.RESERVED);

        assertThat(reservedTicket.getReservationId())
                .isEqualTo(reservationId);

        assertThat(reservedTicket.getReservedUntil())
                .isNotNull();
    }

    private Ticket createAvailableTicket() {
        Event event = new Event();
        event.setTitle("Concurrency test event");
        event.setDescription("Event for ticket reservation concurrency test");
        event.setEventDate(Instant.now().plusSeconds(3600));
        event.setVenue("Test venue");

        Event savedEvent = eventRepository.save(event);

        Ticket ticket = new Ticket();
        ticket.setEvent(savedEvent);
        ticket.setSeatNumber("A-1");
        ticket.setPrice(new BigDecimal("100.00"));
        ticket.setStatus(TicketStatus.AVAILABLE);

        return ticketRepository.save(ticket);
    }
}