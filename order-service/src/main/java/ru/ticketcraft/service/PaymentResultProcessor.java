package ru.ticketcraft.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.dto.PaymentFailedEvent;
import ru.ticketcraft.dto.PaymentSucceededEvent;
import ru.ticketcraft.dto.ReleaseTicketCommand;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.OrderRepository;
import ru.ticketcraft.repository.OrderSagaRepository;
import ru.ticketcraft.repository.ProcessedEventRepository;
import ru.ticketcraft.saga.OrderSaga;
import ru.ticketcraft.saga.OrderSagaStatus;

@Service
public class PaymentResultProcessor {

    private final ProcessedEventRepository processedEventRepository;
    private final OrderRepository orderRepository;
    private final OrderSagaRepository orderSagaRepository;
    private final OutboxService outboxService;

    public PaymentResultProcessor(ProcessedEventRepository processedEventRepository, OrderRepository orderRepository,
            OrderSagaRepository orderSagaRepository, OutboxService outboxService) {

        this.processedEventRepository = processedEventRepository;
        this.orderRepository = orderRepository;
        this.orderSagaRepository = orderSagaRepository;
        this.outboxService = outboxService;
    }

    @Transactional
    public void process(PaymentSucceededEvent event) {

        if (!processedEventRepository.insertIfAbsent(event.messageId())) {
            return;
        }

        loadSaga(event.orderId());

        Order order = loadOrder(event.orderId());

        validateAmount(order, event.amount());

        transitionSaga(order.getId(), OrderSagaStatus.WAITING_FOR_PAYMENT, OrderSagaStatus.COMPLETED);

        transitionOrder(order.getId(), OrderState.PAYMENT_PENDING, OrderState.CONFIRMED);
    }

    @Transactional
    public void process(PaymentFailedEvent event) {

        if (!processedEventRepository.insertIfAbsent(event.messageId())) {
            return;
        }

        OrderSaga saga = loadSaga(event.orderId());

        Order order = loadOrder(event.orderId());

        validateAmount(order, event.amount());

        transitionSaga(order.getId(), OrderSagaStatus.WAITING_FOR_PAYMENT, OrderSagaStatus.COMPENSATING_RESERVATION);

        transitionOrder(order.getId(), OrderState.PAYMENT_PENDING, OrderState.PAYMENT_FAILED);

        ReleaseTicketCommand command = createReleaseTicketCommand(saga, order);

        outboxService.saveReleaseTicketCommand(order, command);
    }

    private OrderSaga loadSaga(Long orderId) {

        return orderSagaRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("Saga not found for order " + orderId));
    }

    private Order loadOrder(Long orderId) {

        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
    }

    private void validateAmount(Order order, java.math.BigDecimal paymentAmount) {

        if (order.getTotalPrice().compareTo(paymentAmount) != 0) {

            throw new IllegalStateException("Payment amount mismatch" + ": orderId=" + order.getId()
                    + ", expectedAmount=" + order.getTotalPrice() + ", actualAmount=" + paymentAmount);
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

    private ReleaseTicketCommand createReleaseTicketCommand(OrderSaga saga, Order order) {

        String messageId = "saga:" + saga.getId() + ":release-ticket";

        return new ReleaseTicketCommand(messageId, order.getId(), saga.getId(), order.getTicketId(), Instant.now());
    }
}