package ru.ticketcraft.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketcraft.payment.provider")
public record PaymentProviderProperties(String baseUrl,
                                        String chargePath,
                                        String refundPath,
                                        String chargeStatusPath,
                                        String refundStatusPath,
                                        Duration connectTimeout,
                                        Duration readTimeout) {

    public PaymentProviderProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("ticketcraft.payment.provider.base-url must not be blank");
        }

        if (chargePath == null || chargePath.isBlank()) {
            throw new IllegalArgumentException("ticketcraft.payment.provider.charge-path must not be blank");
        }

        if (refundPath == null || refundPath.isBlank()) {
            throw new IllegalArgumentException("ticketcraft.payment.provider.refund-path must not be blank");
        }

        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("ticketcraft.payment.provider.connect-timeout must be positive");
        }

        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("ticketcraft.payment.provider.read-timeout must be positive");
        }

        if (chargeStatusPath == null || chargeStatusPath.isBlank()) {
            throw new IllegalArgumentException("ticketcraft.payment.provider.charge-status-path must not be blank");
        }

        if (refundStatusPath == null || refundStatusPath.isBlank()) {
            throw new IllegalArgumentException("ticketcraft.payment.provider.refund-status-path must not be blank");
        }
    }
}