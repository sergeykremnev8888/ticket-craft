package ru.ticketcraft.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.OutboxEventRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxService {

    private static final String AGGREGATE_TYPE = "ORDER";
    private static final String EVENT_TYPE = "OrderCreated";

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public UUID saveOrderCreatedEvent(Order order, OrderEvent event) {
        String payload = serialize(event);

        int inserted = repository.insert(event.getEventId(), AGGREGATE_TYPE, order.getId().toString(), EVENT_TYPE, payload,
                order.getCreatedAt());

        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert outbox event for order: " + order.getId());
        }

        return event.getEventId();
    }

    private String serialize(OrderEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize OrderEvent", e);
        }
    }
}