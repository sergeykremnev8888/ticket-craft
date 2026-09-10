package ru.ticketcraft.service;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.exception.InvalidOrderStateTransitionException;

@Component
public class OrderStateMachine {

    private final Map<OrderState, Set<OrderState>> transitions = Map.of(OrderState.CREATED,
            Set.of(OrderState.TICKETS_RESERVED, OrderState.CANCELED),
            OrderState.TICKETS_RESERVED, Set.of(OrderState.PAYMENT_PENDING, OrderState.CANCELED),
            OrderState.PAYMENT_PENDING, Set.of(OrderState.CONFIRMED, OrderState.PAYMENT_FAILED),
            OrderState.PAYMENT_FAILED, Set.of(OrderState.CANCELED),
            OrderState.CONFIRMED, Set.of(OrderState.DELIVERED),
            OrderState.CANCELED, Set.of(),
            OrderState.DELIVERED, Set.of());

    public void validateTransition(OrderState currentState, OrderState targetState) {
        Set<OrderState> allowedStates = transitions.getOrDefault(currentState, Set.of());

        if (!allowedStates.contains(targetState)) {
            throw new InvalidOrderStateTransitionException(
                    "Invalid order state transition: " + currentState + " -> " + targetState);
        }
    }
}