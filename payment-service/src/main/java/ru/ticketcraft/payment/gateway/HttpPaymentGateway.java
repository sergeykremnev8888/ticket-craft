package ru.ticketcraft.payment.gateway;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import ru.ticketcraft.payment.config.PaymentProviderProperties;

@Component
public class HttpPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpPaymentGateway.class);

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final RestClient restClient;
    private final PaymentProviderProperties properties;

    public HttpPaymentGateway(RestClient.Builder restClientBuilder, PaymentProviderProperties properties) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                                                        .withTimeouts(properties.connectTimeout(),
                                                                      properties.readTimeout());

        this.restClient = restClientBuilder.baseUrl(properties.baseUrl())
                                           .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                                           .build();
        this.properties = properties;
    }

    @Override
    public PaymentResult charge(UUID paymentId, Long orderId, Long userId, BigDecimal amount) {
        ChargeRequest request = new ChargeRequest(paymentId, orderId, userId, amount);
        return executeOperation(properties.chargePath(), properties.chargeStatusPath(), paymentId.toString(), request);
    }

    @Override
    public PaymentResult refund(UUID paymentId, Long orderId, BigDecimal amount) {
        RefundRequest request = new RefundRequest(paymentId, orderId, amount);
        return executeOperation(properties.refundPath(), properties.refundStatusPath(), paymentId + ":refund", request);
    }

    private PaymentResult executeOperation(String operationPath,
                                           String statusPath,
                                           String idempotencyKey,
                                           Object request) {
        try {
            ProviderResponse response = restClient.post()
                                                  .uri(operationPath)
                                                  .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                                                  .contentType(MediaType.APPLICATION_JSON)
                                                  .accept(MediaType.APPLICATION_JSON)
                                                  .body(request)
                                                  .retrieve()
                                                  .body(ProviderResponse.class);

            return mapResponse(response);

        } catch (RestClientException exception) {
            if (!shouldCheckStatus(exception)) {
                throw exception;
            }

            return recoverResult(statusPath, idempotencyKey, exception);
        }
    }

    private boolean shouldCheckStatus(RestClientException exception) {
        if (exception instanceof ResourceAccessException) {
            return true;
        }

        if (exception instanceof RestClientResponseException responseException) {
            return responseException.getStatusCode().is5xxServerError()
                    || responseException.getStatusCode().value() == 429;
        }

        return false;
    }

    private PaymentResult recoverResult(String statusPath,
                                        String idempotencyKey,
                                        RestClientException originalException) {
        log.warn("Payment provider POST failed; checking operation status " + "[idempotencyKey={}, error={}]",
                 idempotencyKey,
                 originalException.getMessage());

        try {
            Optional<ProviderResponse> response = findOperationResult(statusPath, idempotencyKey);
            if (response.isPresent()) {
                PaymentResult result = mapResponse(response.get());

                log.info("Payment operation result recovered from provider " + "[idempotencyKey={}, successful={}]",
                         idempotencyKey,
                         result.successful());

                return result;
            }

            log.warn("Payment operation result is not available yet " + "[idempotencyKey={}]", idempotencyKey);

        } catch (RuntimeException statusException) {
            originalException.addSuppressed(statusException);

            log.warn("Failed to resolve payment operation status " + "[idempotencyKey={}]",
                     idempotencyKey,
                     statusException);
        }

        throw originalException;
    }

    private Optional<ProviderResponse> findOperationResult(String statusPath, String idempotencyKey) {
        try {
            ProviderResponse response = restClient.get()
                                                  .uri(statusPath)
                                                  .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                                                  .accept(MediaType.APPLICATION_JSON)
                                                  .retrieve()
                                                  .body(ProviderResponse.class);

            if (response == null) {
                throw new IllegalStateException("Payment provider returned an empty status response");
            }

            return Optional.of(response);

        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        }
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