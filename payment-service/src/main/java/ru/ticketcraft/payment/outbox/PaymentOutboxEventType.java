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

    public static PaymentOutboxEventType fromPersistedValue(String value) {

        for (PaymentOutboxEventType eventType : values()) {
            if (eventType.persistedValue.equals(value)) {
                return eventType;
            }
        }

        throw new IllegalArgumentException("Unsupported payment outbox event type: " + value);
    }
}