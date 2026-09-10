package ru.ticketcraft.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.dto.ReserveTicketCommand;
import ru.ticketcraft.exception.OrderNotFoundException;
import ru.ticketcraft.idempotency.CanonicalOrderRequest;
import ru.ticketcraft.model.IdempotencyKey;
import ru.ticketcraft.model.IdempotencyStatus;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.OrderRepository;
import ru.ticketcraft.repository.OrderSagaRepository;
import ru.ticketcraft.saga.OrderSagaStatus;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OutboxService outboxService;
    private final IdempotencyService idempotencyService;
    private final RequestHashService requestHashService;
    private final OrderStateMachine orderStateMachine;
    private final OrderSagaRepository orderSagaRepository;

    public OrderService(OrderRepository orderRepository, OutboxService outboxService,
            IdempotencyService idempotencyService, RequestHashService requestHashService,
            OrderStateMachine orderStateMachine, OrderSagaRepository orderSagaRepository) {
        this.orderRepository = orderRepository;
        this.outboxService = outboxService;
        this.idempotencyService = idempotencyService;
        this.requestHashService = requestHashService;
        this.orderStateMachine = orderStateMachine;
        this.orderSagaRepository = orderSagaRepository;
    }

    @Transactional
    public Order createOrder(String idempotencyKey, Long userId, UUID eventId, UUID ticketId, BigDecimal price) {
        CanonicalOrderRequest request = new CanonicalOrderRequest(userId, eventId, ticketId, price);
        String requestHash = requestHashService.hash(request);

        IdempotencyKey key = idempotencyService.checkAndRegister(idempotencyKey, userId, requestHash);
        if (key.getStatus() == IdempotencyStatus.COMPLETED) {
            return orderRepository.findById(key.getOrderId()).orElseThrow(() -> new IllegalStateException(
                    "Completed idempotency key references missing order: " + key.getOrderId()));
        }

        Instant now = Instant.now();
        Order order = new Order(null, userId, eventId, ticketId, price, OrderState.CREATED, now);
        Order savedOrder = orderRepository.save(order);

        UUID sagaId = UUID.randomUUID();
        int sagaInserted = orderSagaRepository.insertIfAbsent(sagaId, savedOrder.getId(),
                OrderSagaStatus.WAITING_FOR_RESERVATION.name(), now, now);
        if (sagaInserted != 1) {
            throw new IllegalStateException("Failed to create saga for order: " + savedOrder.getId());
        }

        String messageId = "saga:" + sagaId + ":reserve-ticket";
        ReserveTicketCommand command = new ReserveTicketCommand(messageId, savedOrder.getId(), sagaId,
                savedOrder.getTicketId(), savedOrder.getUserId(), now);
        outboxService.saveReserveTicketCommand(savedOrder, command);
        idempotencyService.complete(idempotencyKey, savedOrder.getId());

        return savedOrder;
    }

    @Transactional
    public Order transitionTo(Long orderId, OrderState targetState) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));

        orderStateMachine.validateTransition(order.getStatus(), targetState);

        order.setStatus(targetState);

        return orderRepository.save(order);
    }
}