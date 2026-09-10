package ru.ticketcraft.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.dto.PaymentRequestedEvent;
import ru.ticketcraft.dto.TicketReservationFailedEvent;
import ru.ticketcraft.dto.TicketReservedEvent;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.OrderRepository;
import ru.ticketcraft.repository.OrderSagaRepository;
import ru.ticketcraft.repository.ProcessedEventRepository;
import ru.ticketcraft.saga.OrderSaga;
import ru.ticketcraft.saga.OrderSagaStatus;

@Service
public class TicketReservationResultProcessor {

    private final ProcessedEventRepository processedEventRepository;
    private final OrderRepository orderRepository;
    private final OrderSagaRepository orderSagaRepository;
    private final OutboxService outboxService;

    public TicketReservationResultProcessor(ProcessedEventRepository processedEventRepository,
            OrderRepository orderRepository, OrderSagaRepository orderSagaRepository, OutboxService outboxService) {

        this.processedEventRepository = processedEventRepository;
        this.orderRepository = orderRepository;
        this.orderSagaRepository = orderSagaRepository;
        this.outboxService = outboxService;
    }

    @Transactional
    public void process(TicketReservedEvent event) {

        if (!processedEventRepository.insertIfAbsent(event.messageId())) {

            return;
        }

        OrderSaga saga = loadAndValidateSaga(event.orderId(), event.reservationId());

        Order order = loadOrder(event.orderId());

        validateReservationTarget(order, event.ticketId());

        transitionSaga(saga.getOrderId(), OrderSagaStatus.WAITING_FOR_RESERVATION, OrderSagaStatus.WAITING_FOR_PAYMENT);

        transitionOrder(order.getId(), OrderState.CREATED, OrderState.TICKETS_RESERVED);

        transitionOrder(order.getId(), OrderState.TICKETS_RESERVED, OrderState.PAYMENT_PENDING);

        PaymentRequestedEvent paymentRequestedEvent = createPaymentRequestedEvent(saga, order);

        outboxService.savePaymentRequestedEvent(order, paymentRequestedEvent);
    }

    @Transactional
    public void process(TicketReservationFailedEvent event) {

        if (!processedEventRepository.insertIfAbsent(event.messageId())) {

            return;
        }

        OrderSaga saga = loadAndValidateSaga(event.orderId(), event.reservationId());

        Order order = loadOrder(event.orderId());

        validateReservationTarget(order, event.ticketId());

        transitionSaga(saga.getOrderId(), OrderSagaStatus.WAITING_FOR_RESERVATION, OrderSagaStatus.FAILED);

        transitionOrder(order.getId(), OrderState.CREATED, OrderState.CANCELED);
    }

    private OrderSaga loadAndValidateSaga(Long orderId, UUID reservationId) {

        OrderSaga saga = orderSagaRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("Saga not found for order " + orderId));

        if (!saga.getId().equals(reservationId)) {
            throw new IllegalStateException("Reservation result correlation mismatch" + ": orderId=" + orderId
                    + ", expectedReservationId=" + saga.getId() + ", actualReservationId=" + reservationId);
        }

        return saga;
    }

    private Order loadOrder(Long orderId) {

        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
    }

    private void validateReservationTarget(Order order, UUID ticketId) {

        if (!order.getTicketId().equals(ticketId)) {
            throw new IllegalStateException("Reservation result ticket mismatch" + ": orderId=" + order.getId()
                    + ", expectedTicketId=" + order.getTicketId() + ", actualTicketId=" + ticketId);
        }
    }

    private void transitionSaga(Long orderId, OrderSagaStatus expected, OrderSagaStatus target) {

        int updated = orderSagaRepository.transition(orderId, expected.name(), target.name(), Instant.now());

        if (updated != 1) {
            throw new IllegalStateException(
                    "Failed to transition saga for order " + orderId + " from " + expected + " to " + target);
        }
    }

    private void transitionOrder(Long orderId, OrderState expected, OrderState target) {

        boolean transitioned = orderRepository.transitionStatus(orderId, expected, target);

        if (!transitioned) {
            throw new IllegalStateException(
                    "Failed to transition order " + orderId + " from " + expected + " to " + target);
        }
    }

    private PaymentRequestedEvent createPaymentRequestedEvent(OrderSaga saga, Order order) {

        String messageId = "saga:" + saga.getId() + ":payment-requested";

        return new PaymentRequestedEvent(messageId, order.getId(), order.getUserId(), order.getTotalPrice(),
                Instant.now());
    }
}