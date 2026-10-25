package ru.ticketcraft.payment.outbox;

public enum PaymentOutboxEventType {

    PAYMENT_SUCCEEDED("PaymentSucceededEvent"),
    PAYMENT_FAILED("PaymentFailedEvent"),
    PAYMENT_REFUNDED("PaymentRefundedEvent");

    private final String persistedValue;

    PaymentOutboxEventType(String persistedValue) {
        this.persistedValue = persistedValue;
    }

    public String getPersistedValue() {
        return persistedValue;
    }
}