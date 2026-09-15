package ru.ticketcraft.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import ru.ticketcraft.payment.config.PaymentProviderProperties;

@Component
public class HttpPaymentGateway implements PaymentGateway {

    private final RestClient restClient;
    private final PaymentProviderProperties properties;

    public HttpPaymentGateway(RestClient.Builder restClientBuilder, PaymentProviderProperties properties) {

        HttpClientSettings settings = HttpClientSettings.defaults().withTimeouts(properties.connectTimeout(),
                properties.readTimeout());

        this.restClient = restClientBuilder.baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings)).build();

        this.properties = properties;
    }

    @Override
    public PaymentResult charge(UUID paymentId, Long orderId, Long userId, BigDecimal amount) {

        ChargeRequest request = new ChargeRequest(paymentId, orderId, userId, amount);

        ProviderResponse response = restClient.post().uri(properties.chargePath())
                .header("Idempotency-Key", paymentId.toString()).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).body(request).retrieve().body(ProviderResponse.class);

        return mapResponse(response);
    }

    @Override
    public PaymentResult refund(UUID paymentId, Long orderId, BigDecimal amount) {

        RefundRequest request = new RefundRequest(paymentId, orderId, amount);

        ProviderResponse response = restClient.post().uri(properties.refundPath())
                .header("Idempotency-Key", paymentId + ":refund").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).body(request).retrieve().body(ProviderResponse.class);

        return mapResponse(response);
    }

    private PaymentResult mapResponse(ProviderResponse response) {

        if (response == null || response.status() == null) {
            throw new IllegalStateException("Payment provider returned an invalid response");
        }

        return switch (response.status()) {

        case APPROVED -> PaymentResult.success();

        case DECLINED ->
            PaymentResult.failure(response.reason() == null ? "PAYMENT_OPERATION_DECLINED" : response.reason());
        };
    }

    private record ChargeRequest(UUID paymentId, Long orderId, Long userId, BigDecimal amount) {
    }

    private record RefundRequest(UUID paymentId, Long orderId, BigDecimal amount) {
    }

    private record ProviderResponse(ProviderStatus status, String reason) {
    }

    private enum ProviderStatus {
        APPROVED, DECLINED
    }
}