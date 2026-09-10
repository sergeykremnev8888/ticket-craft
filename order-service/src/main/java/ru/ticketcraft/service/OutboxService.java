package ru.ticketcraft.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ru.ticketcraft.config.KafkaTopicsProperties;
import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.model.Order;
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

    public UUID saveOrderCreatedEvent(Order order, OrderEvent event) {
        UUID eventId = event.getEventId();

        save(eventId, AGGREGATE_TYPE_ORDER, order.getId().toString(), OutboxEventType.ORDER_CREATED,
                topics.orderEvents(), event, order.getCreatedAt());

        return eventId;
    }

    public UUID saveReserveTicketCommand(Order order, ReserveTicketCommand command) {

        UUID eventId = UUID.randomUUID();

        save(eventId, AGGREGATE_TYPE_ORDER, order.getId().toString(), OutboxEventType.RESERVE_TICKET,
                topics.ticketReservationCommands(), command, command.occurredAt());

        return eventId;
    }

    public void save(UUID eventId, String aggregateType, String aggregateId, OutboxEventType eventType, String topic,
            Object payload, Instant createdAt) {

        String serializedPayload = serialize(payload);

        int inserted = repository.insert(eventId, aggregateType, aggregateId, eventType.getValue(), topic,
                serializedPayload, createdAt);

        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert outbox event: " + eventId);
        }
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