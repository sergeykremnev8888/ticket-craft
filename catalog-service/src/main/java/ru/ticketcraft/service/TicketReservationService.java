package ru.ticketcraft.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.config.ReservationProperties;
import ru.ticketcraft.dto.ConfirmTicketCommand;
import ru.ticketcraft.dto.ReleaseTicketCommand;
import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.dto.TicketConfirmedEvent;
import ru.ticketcraft.dto.TicketReleasedEvent;
import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketReservedEvent;
import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.TicketRepository;

@Service
public class TicketReservationService {

    private static final String FAILURE_TICKET_NOT_FOUND = "TICKET_NOT_FOUND";

    private static final String FAILURE_TICKET_ALREADY_RESERVED = "TICKET_ALREADY_RESERVED";

    private final TicketRepository ticketRepository;
    private final OutboxService outboxService;
    private final Duration reservationDuration;

    public TicketReservationService(TicketRepository ticketRepository, OutboxService outboxService,
            ReservationProperties properties) {

        this.ticketRepository = ticketRepository;
        this.outboxService = outboxService;
        this.reservationDuration = properties.duration();
    }

    /*
     * Saga / Kafka flow.
     *
     * Reservation state change и result outbox event находятся в одной PostgreSQL
     * transaction.
     */
    @Transactional
    public void processReserveTicketCommand(ReserveTicketCommand command) {
        String resultMessageId = resultMessageId(command);

        if (outboxService.existsByMessageId(resultMessageId)) {
            return;
        }

        Instant now = Instant.now();

        Instant reservedUntil = now.plus(reservationDuration);

        int updated = ticketRepository.reserveTicket(command.ticketId(), command.reservationId(), reservedUntil);

        /*
         * Первый успешный reserve.
         */
        if (updated == 1) {

            Ticket ticket = ticketRepository.findById(command.ticketId()).orElseThrow(
                    () -> new IllegalStateException("Reserved ticket disappeared: ticketId=" + command.ticketId()));

            saveTicketReservedResult(command, ticket, now);

            return;
        }

        /*
         * UPDATE не выполнился.
         *
         * Нужно понять причину: - ticket не существует; - duplicate той же Saga; -
         * ticket принадлежит другой reservation.
         */
        Optional<Ticket> optionalTicket = ticketRepository.findById(command.ticketId());

        if (optionalTicket.isEmpty()) {

            saveTicketReservationFailedResult(command, FAILURE_TICKET_NOT_FOUND, now);

            return;
        }

        Ticket ticket = optionalTicket.get();

        /*
         * Kafka redelivery той же Saga-команды.
         *
         * Билет уже зарезервирован именно этой reservation. Это идемпотентный success.
         *
         * Повторный outbox event не появится благодаря UNIQUE(message_id) + ON CONFLICT
         * DO NOTHING.
         */
        if (ticket.getStatus() == TicketStatus.RESERVED && command.reservationId().equals(ticket.getReservationId())) {

            saveTicketReservedResult(command, ticket, now);

            return;
        }

        /*
         * Билет существует, но зарезервировать его данной Saga нельзя.
         */
        saveTicketReservationFailedResult(command, FAILURE_TICKET_ALREADY_RESERVED, now);
    }

    @Transactional
    public void processReleaseTicketCommand(ReleaseTicketCommand command) {

        String resultMessageId = resultMessageId(command);

        if (outboxService.existsByMessageId(resultMessageId)) {

            return;
        }

        Instant now = Instant.now();

        int updated = ticketRepository.releaseTicket(command.ticketId(), command.reservationId());

        if (updated == 1) {

            saveTicketReleasedResult(command, now);

            return;
        }

        /*
         * UPDATE не сработал.
         *
         * Нужно отличить:
         *
         * 1. билет отсутствует; 2. reservation уже снята; 3. билет сейчас принадлежит
         * другой reservation.
         */
        Optional<Ticket> optionalTicket = ticketRepository.findById(command.ticketId());

        if (optionalTicket.isEmpty()) {

            throw new IllegalStateException("Cannot release reservation: ticket not found" + ": ticketId="
                    + command.ticketId() + ", reservationId=" + command.reservationId());
        }

        Ticket ticket = optionalTicket.get();

        /*
         * Reservation уже отсутствует.
         *
         * Это допустимый idempotent completion.
         *
         * Например: - reservation была освобождена раньше; - TTL worker успел снять её
         * до compensation command.
         *
         * Для Saga требуемый postcondition уже достигнут: данная reservation больше не
         * держит билет.
         */
        if (ticket.getStatus() == TicketStatus.AVAILABLE) {

            saveTicketReleasedResult(command, now);

            return;
        }

        /*
         * RESERVED, но releaseTicket() вернул 0.
         *
         * Значит reservationId не совпал.
         *
         * Критически важно НЕ освобождать такой билет.
         */
        throw new IllegalStateException("Cannot release ticket reserved by another reservation" + ": ticketId="
                + command.ticketId() + ", expectedReservationId=" + command.reservationId() + ", actualReservationId="
                + ticket.getReservationId());
    }

    @Transactional
    public void processConfirmTicketCommand(ConfirmTicketCommand command) {

        String resultMessageId = resultMessageId(command);

        if (outboxService.existsByMessageId(resultMessageId)) {
            return;
        }

        Instant now = Instant.now();

        int updated = ticketRepository.confirmTicket(command.ticketId(), command.reservationId());

        if (updated == 1) {
            saveTicketConfirmedResult(command, now);
            return;
        }

        Optional<Ticket> optionalTicket = ticketRepository.findById(command.ticketId());

        if (optionalTicket.isEmpty()) {
            throw new IllegalStateException("Cannot confirm ticket: ticket not found" + ": ticketId="
                    + command.ticketId() + ", reservationId=" + command.reservationId());
        }

        Ticket ticket = optionalTicket.get();

        /*
         * Kafka redelivery после уже успешно выполненного RESERVED -> SOLD.
         */
        if (ticket.getStatus() == TicketStatus.SOLD && command.reservationId().equals(ticket.getReservationId())) {

            saveTicketConfirmedResult(command, now);
            return;
        }

        /*
         * Важно:
         *
         * AVAILABLE означает, что reservation могла истечь до того, как пришёл
         * ConfirmTicketCommand.
         *
         * Такой билет нельзя снова переводить в SOLD.
         */
        throw new IllegalStateException("Cannot confirm ticket reservation" + ": ticketId=" + command.ticketId()
                + ", expectedReservationId=" + command.reservationId() + ", actualReservationId="
                + ticket.getReservationId() + ", status=" + ticket.getStatus());
    }

    private void saveTicketConfirmedResult(ConfirmTicketCommand command, Instant occurredAt) {

        String messageId = resultMessageId(command);

        TicketConfirmedEvent event = new TicketConfirmedEvent(messageId, command.orderId(), command.reservationId(),
                command.ticketId(), occurredAt);

        outboxService.saveTicketConfirmedEvent(event);
    }

    private String resultMessageId(ConfirmTicketCommand command) {
        return "result:" + command.messageId();
    }

    private void saveTicketReleasedResult(ReleaseTicketCommand command, Instant occurredAt) {

        String messageId = resultMessageId(command);

        TicketReleasedEvent event = new TicketReleasedEvent(messageId, command.orderId(), command.reservationId(),
                command.ticketId(), occurredAt);

        outboxService.saveTicketReleasedEvent(event);
    }

    private String resultMessageId(ReleaseTicketCommand command) {

        return "result:" + command.messageId();
    }

    private void saveTicketReservedResult(ReserveTicketCommand command, Ticket ticket, Instant occurredAt) {

        String messageId = resultMessageId(command);

        TicketReservedEvent event = new TicketReservedEvent(messageId, command.orderId(), command.reservationId(),
                command.ticketId(), ticket.getEvent().getId(), ticket.getPrice(), occurredAt);

        outboxService.saveTicketReservedEvent(event);
    }

    private void saveTicketReservationFailedResult(ReserveTicketCommand command, String reason, Instant occurredAt) {

        String messageId = resultMessageId(command);

        TicketReservationFailedEvent event = new TicketReservationFailedEvent(messageId, command.orderId(),
                command.reservationId(), command.ticketId(), reason, occurredAt);

        outboxService.saveTicketReservationFailedEvent(event);
    }

    private String resultMessageId(ReserveTicketCommand command) {
        return "result:" + command.messageId();
    }
}