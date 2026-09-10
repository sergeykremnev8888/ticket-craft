package ru.ticketcraft.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ru.ticketcraft.config.KafkaTopicsProperties;
import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketReservedEvent;
import ru.ticketcraft.model.OutboxEventType;
import ru.ticketcraft.repository.OutboxEventRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxService {

    private static final String AGGREGATE_TYPE_ORDER = "ORDER";

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final KafkaTopicsProperties topics;

    public OutboxService(OutboxEventRepository repository, ObjectMapper objectMapper, KafkaTopicsProperties topics) {

        this.repository = repository;
        this.objectMapper = objectMapper;
        this.topics = topics;
    }

    public boolean saveTicketReservedEvent(TicketReservedEvent event) {

        return save(event.messageId(), event.orderId().toString(), OutboxEventType.TICKET_RESERVED, event,
                event.occurredAt());
    }

    public boolean saveTicketReservationFailedEvent(TicketReservationFailedEvent event) {

        return save(event.messageId(), event.orderId().toString(), OutboxEventType.TICKET_RESERVATION_FAILED, event,
                event.occurredAt());
    }

    public boolean existsByMessageId(String messageId) {
        return repository.existsByMessageId(messageId);
    }

    private boolean save(String messageId, String aggregateId, OutboxEventType eventType, Object payload,
            Instant createdAt) {

        String serializedPayload = serialize(payload);

        return repository.insertIfAbsent(UUID.randomUUID(), messageId, AGGREGATE_TYPE_ORDER, aggregateId,
                eventType.getValue(), topics.ticketReservationResults(), serializedPayload, createdAt);
    }

    private String serialize(Object payload) {

        try {

            return objectMapper.writeValueAsString(payload);

        } catch (JacksonException e) {

            throw new IllegalStateException("Failed to serialize outbox payload: " + payload.getClass().getSimpleName(),
                    e);
        }
    }

}