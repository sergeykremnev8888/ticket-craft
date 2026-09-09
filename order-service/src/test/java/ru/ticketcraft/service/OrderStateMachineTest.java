package ru.ticketcraft.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.exception.InvalidOrderStateTransitionException;

class OrderStateMachineTest {

    private final OrderStateMachine stateMachine = new OrderStateMachine();

    @ParameterizedTest(name = "{0} -> {1} should be allowed")
    @MethodSource("validTransitions")
    void shouldAllowValidTransition(OrderState currentState, OrderState targetState) {

        assertThatCode(() -> stateMachine.validateTransition(currentState, targetState)).doesNotThrowAnyException();
    }

    private static Stream<Arguments> validTransitions() {
        return Stream.of(Arguments.of(OrderState.CREATED, OrderState.TICKETS_RESERVED),
                Arguments.of(OrderState.TICKETS_RESERVED, OrderState.PAYMENT_PENDING),
                Arguments.of(OrderState.TICKETS_RESERVED, OrderState.CANCELED),
                Arguments.of(OrderState.PAYMENT_PENDING, OrderState.CONFIRMED),
                Arguments.of(OrderState.PAYMENT_PENDING, OrderState.PAYMENT_FAILED),
                Arguments.of(OrderState.PAYMENT_FAILED, OrderState.CANCELED),
                Arguments.of(OrderState.CONFIRMED, OrderState.DELIVERED));
    }

    @ParameterizedTest(name = "{0} -> {1} should be rejected")
    @MethodSource("invalidTransitions")
    void shouldRejectInvalidTransition(OrderState currentState, OrderState targetState) {

        assertThatThrownBy(() -> stateMachine.validateTransition(currentState, targetState))
                .isInstanceOf(InvalidOrderStateTransitionException.class);
    }

    private static Stream<Arguments> invalidTransitions() {
        return Stream.of(Arguments.of(OrderState.CREATED, OrderState.CONFIRMED),
                Arguments.of(OrderState.CREATED, OrderState.PAYMENT_PENDING),
                Arguments.of(OrderState.TICKETS_RESERVED, OrderState.CONFIRMED),
                Arguments.of(OrderState.PAYMENT_FAILED, OrderState.CONFIRMED),
                Arguments.of(OrderState.CANCELED, OrderState.CREATED),
                Arguments.of(OrderState.CANCELED, OrderState.CONFIRMED),
                Arguments.of(OrderState.CONFIRMED, OrderState.CANCELED),
                Arguments.of(OrderState.DELIVERED, OrderState.CANCELED));
    }
}