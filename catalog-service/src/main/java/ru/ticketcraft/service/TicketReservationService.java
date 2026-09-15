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
import ru.ticketcraft.dto.TicketConfirmationFailedEvent;
import ru.ticketcraft.dto.TicketConfirmationFailureReason;
import ru.ticketcraft.dto.TicketConfirmedEvent;
import ru.ticketcraft.dto.TicketReleasedEvent;
import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketReservedEvent;
import ru.ticketcraft.dto.TicketStatus;
import ru.ticketcraft.model.Ticket;
import ru.ticketcraft.repository.TicketRepository;

@Service
public class TicketReservationService {

    private static final String FAILURE_TICKET_NOT_FOUND =
            "TICKET_NOT_FOUND";

    private static final String FAILURE_TICKET_ALREADY_RESERVED =
            "TICKET_ALREADY_RESERVED";

    private final TicketRepository ticketRepository;
    private final OutboxService outboxService;
    private final Duration reservationDuration;

    public TicketReservationService(
            TicketRepository ticketRepository,
            OutboxService outboxService,
            ReservationProperties properties) {

        this.ticketRepository = ticketRepository;
        this.outboxService = outboxService;
        this.reservationDuration = properties.duration();
    }

    @Transactional
    public void processReserveTicketCommand(ReserveTicketCommand command) {

        String resultMessageId = resultMessageId(command);

        if (outboxService.existsByMessageId(resultMessageId)) {
            return;
        }

        Instant now = Instant.now();
        Instant reservedUntil = now.plus(reservationDuration);

        int updated = ticketRepository.reserveTicket(
                command.ticketId(),
                command.reservationId(),
                reservedUntil);

        if (updated == 1) {

            Ticket ticket = ticketRepository.findById(command.ticketId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Reserved ticket disappeared: ticketId="
                                    + command.ticketId()));

            saveTicketReservedResult(command, ticket, now);
            return;
        }

        Optional<Ticket> optionalTicket =
                ticketRepository.findById(command.ticketId());

        if (optionalTicket.isEmpty()) {

            saveTicketReservationFailedResult(
                    command,
                    FAILURE_TICKET_NOT_FOUND,
                    now);

            return;
        }

        Ticket ticket = optionalTicket.get();

        if (ticket.getStatus() == TicketStatus.RESERVED
                && command.reservationId().equals(ticket.getReservationId())) {

            saveTicketReservedResult(command, ticket, now);
            return;
        }

        saveTicketReservationFailedResult(
                command,
                FAILURE_TICKET_ALREADY_RESERVED,
                now);
    }

    @Transactional
    public void processReleaseTicketCommand(ReleaseTicketCommand command) {

        String resultMessageId = resultMessageId(command);

        if (outboxService.existsByMessageId(resultMessageId)) {
            return;
        }

        Instant now = Instant.now();

        int updated = ticketRepository.releaseTicket(
                command.ticketId(),
                command.reservationId());

        if (updated == 1) {
            saveTicketReleasedResult(command, now);
            return;
        }

        Optional<Ticket> optionalTicket =
                ticketRepository.findById(command.ticketId());

        if (optionalTicket.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot release reservation: ticket not found"
                            + ": ticketId=" + command.ticketId()
                            + ", reservationId=" + command.reservationId());
        }

        Ticket ticket = optionalTicket.get();

        if (ticket.getStatus() == TicketStatus.AVAILABLE) {
            saveTicketReleasedResult(command, now);
            return;
        }

        throw new IllegalStateException(
                "Cannot release ticket reserved by another reservation"
                        + ": ticketId=" + command.ticketId()
                        + ", expectedReservationId=" + command.reservationId()
                        + ", actualReservationId=" + ticket.getReservationId());
    }

    @Transactional
    public void processConfirmTicketCommand(ConfirmTicketCommand command) {

        String resultMessageId = resultMessageId(command);

        if (outboxService.existsByMessageId(resultMessageId)) {
            return;
        }

        Instant now = Instant.now();

        int updated = ticketRepository.confirmTicket(
                command.ticketId(),
                command.reservationId(),
                now);

        if (updated == 1) {
            saveTicketConfirmedResult(command, now);
            return;
        }

        Optional<Ticket> optionalTicket =
                ticketRepository.findById(command.ticketId());

        if (optionalTicket.isEmpty()) {

            saveTicketConfirmationFailedResult(
                    command,
                    TicketConfirmationFailureReason.TICKET_NOT_FOUND,
                    now);

            return;
        }

        Ticket ticket = optionalTicket.get();

        /*
         * Redelivery после уже выполненного RESERVED -> SOLD.
         */
        if (ticket.getStatus() == TicketStatus.SOLD
                && command.reservationId().equals(ticket.getReservationId())) {

            saveTicketConfirmedResult(command, now);
            return;
        }

        /*
         * Reservation всё ещё физически RESERVED, но TTL уже логически истёк.
         * Scheduler мог ещё не успеть перевести билет в AVAILABLE.
         */
        if (ticket.getStatus() == TicketStatus.RESERVED
                && command.reservationId().equals(ticket.getReservationId())
                && ticket.getReservedUntil() != null
                && !ticket.getReservedUntil().isAfter(now)) {

            saveTicketConfirmationFailedResult(
                    command,
                    TicketConfirmationFailureReason.RESERVATION_EXPIRED,
                    now);

            return;
        }

        /*
         * TTL worker уже успел освободить reservation.
         */
        if (ticket.getStatus() == TicketStatus.AVAILABLE) {

            saveTicketConfirmationFailedResult(
                    command,
                    TicketConfirmationFailureReason.RESERVATION_EXPIRED,
                    now);

            return;
        }

        /*
         * Ticket принадлежит другой reservation либо находится
         * в несовместимом состоянии.
         */
        saveTicketConfirmationFailedResult(
                command,
                TicketConfirmationFailureReason.RESERVATION_MISMATCH,
                now);
    }

    private void saveTicketConfirmedResult(
            ConfirmTicketCommand command,
            Instant occurredAt) {

        String messageId = resultMessageId(command);

        TicketConfirmedEvent event =
                new TicketConfirmedEvent(
                        messageId,
                        command.orderId(),
                        command.reservationId(),
                        command.ticketId(),
                        occurredAt);

        outboxService.saveTicketConfirmedEvent(event);
    }

    private void saveTicketConfirmationFailedResult(
            ConfirmTicketCommand command,
            TicketConfirmationFailureReason reason,
            Instant occurredAt) {

        String messageId = resultMessageId(command);

        TicketConfirmationFailedEvent event =
                new TicketConfirmationFailedEvent(
                        messageId,
                        command.orderId(),
                        command.reservationId(),
                        command.ticketId(),
                        reason,
                        occurredAt);

        outboxService.saveTicketConfirmationFailedEvent(event);
    }

    private void saveTicketReleasedResult(
            ReleaseTicketCommand command,
            Instant occurredAt) {

        String messageId = resultMessageId(command);

        TicketReleasedEvent event =
                new TicketReleasedEvent(
                        messageId,
                        command.orderId(),
                        command.reservationId(),
                        command.ticketId(),
                        occurredAt);

        outboxService.saveTicketReleasedEvent(event);
    }

    private void saveTicketReservedResult(
            ReserveTicketCommand command,
            Ticket ticket,
            Instant occurredAt) {

        String messageId = resultMessageId(command);

        TicketReservedEvent event =
                new TicketReservedEvent(
                        messageId,
                        command.orderId(),
                        command.reservationId(),
                        command.ticketId(),
                        ticket.getEvent().getId(),
                        ticket.getPrice(),
                        occurredAt);

        outboxService.saveTicketReservedEvent(event);
    }

    private void saveTicketReservationFailedResult(
            ReserveTicketCommand command,
            String reason,
            Instant occurredAt) {

        String messageId = resultMessageId(command);

        TicketReservationFailedEvent event =
                new TicketReservationFailedEvent(
                        messageId,
                        command.orderId(),
                        command.reservationId(),
                        command.ticketId(),
                        reason,
                        occurredAt);

        outboxService.saveTicketReservationFailedEvent(event);
    }

    private String resultMessageId(ReserveTicketCommand command) {
        return "result:" + command.messageId();
    }

    private String resultMessageId(ReleaseTicketCommand command) {
        return "result:" + command.messageId();
    }

    private String resultMessageId(ConfirmTicketCommand command) {
        return "result:" + command.messageId();
    }
}