package ru.ticketcraft.payment.gateway;

public record PaymentResult(boolean successful, String reason) {

    public static PaymentResult success() {
        return new PaymentResult(true, null);
    }

    public static PaymentResult failure(String reason) {
        return new PaymentResult(false, reason);
    }
}
