package ru.ticketcraft.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ru.ticketcraft.payment.config.PaymentProviderProperties;

class HttpPaymentGatewayTest {

    private static final UUID PAYMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private static final String APPROVED = """
            {"status":"APPROVED","reason":null}
            """;

    private static final String DECLINED = """
            {"status":"DECLINED","reason":"INSUFFICIENT_FUNDS"}
            """;

    private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();

    private final CountDownLatch releasePostResponse = new CountDownLatch(1);

    private HttpServer server;
    private ExecutorService serverExecutor;
    private HttpPaymentGateway gateway;

    private volatile int postStatus = 200;
    private volatile String postBody = APPROVED;

    private volatile int statusResponseCode = 200;
    private volatile String statusResponseBody = APPROVED;

    private volatile boolean delayPostResponse;

    @BeforeEach
    void setUp() throws IOException {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // GET должен обрабатываться, пока POST ждёт освобождения latch.
        serverExecutor = Executors.newFixedThreadPool(2);

        server.setExecutor(serverExecutor);
        server.createContext("/api/v1/payments", this::handleRequest);
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        PaymentProviderProperties properties = new PaymentProviderProperties(baseUrl, "/api/v1/payments/charges",
                "/api/v1/payments/refunds", "/api/v1/payments/charges/status", "/api/v1/payments/refunds/status",
                Duration.ofSeconds(2), Duration.ofSeconds(1));

        gateway = new HttpPaymentGateway(RestClient.builder(), properties);
    }

    @AfterEach
    void tearDown() {

        releasePostResponse.countDown();

        if (server != null) {
            server.stop(0);
        }

        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldReturnApprovedWithoutStatusRequest(Operation operation) {

        PaymentResult result = execute(operation);

        assertThat(result.successful()).isTrue();
        assertThat(result.reason()).isNull();

        assertRequests(operation, false);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldReturnDeclinedWithoutStatusRequest(Operation operation) {

        postBody = DECLINED;

        PaymentResult result = execute(operation);

        assertThat(result.successful()).isFalse();
        assertThat(result.reason()).isEqualTo("INSUFFICIENT_FUNDS");

        assertRequests(operation, false);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldRecoverApprovedResultAfterRealHttpTimeout(Operation operation) {

        delayPostResponse = true;

        PaymentResult result = execute(operation);

        assertThat(result.successful()).isTrue();
        assertThat(result.reason()).isNull();

        assertRequests(operation, true);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldRecoverApprovedResultAfterServerError(Operation operation) {

        postStatus = 503;
        postBody = "{}";

        PaymentResult result = execute(operation);

        assertThat(result.successful()).isTrue();

        assertRequests(operation, true);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldCheckStatusAfterTooManyRequests(Operation operation) {

        postStatus = 429;
        postBody = "{}";

        PaymentResult result = execute(operation);

        assertThat(result.successful()).isTrue();

        assertRequests(operation, true);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldRecoverDeclinedResultFromStatusApi(Operation operation) {

        postStatus = 503;
        postBody = "{}";
        statusResponseBody = DECLINED;

        PaymentResult result = execute(operation);

        assertThat(result.successful()).isFalse();
        assertThat(result.reason()).isEqualTo("INSUFFICIENT_FUNDS");

        assertRequests(operation, true);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldPreservePostErrorWhenStatusIsNotFound(Operation operation) {

        postStatus = 503;
        postBody = "{}";
        statusResponseCode = 404;
        statusResponseBody = "{}";

        Throwable failure = catchThrowable(() -> execute(operation));

        assertHttpStatus(failure, 503);
        assertThat(failure.getSuppressed()).isEmpty();

        assertRequests(operation, true);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldPreserveTimeoutWhenStatusIsNotFound(Operation operation) {

        delayPostResponse = true;
        statusResponseCode = 404;
        statusResponseBody = "{}";

        Throwable failure = catchThrowable(() -> execute(operation));

        assertThat(failure).isInstanceOf(ResourceAccessException.class);

        assertRequests(operation, true);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldAttachStatusErrorToOriginalPostError(Operation operation) {

        postStatus = 503;
        postBody = "{}";
        statusResponseCode = 502;
        statusResponseBody = "{}";

        Throwable failure = catchThrowable(() -> execute(operation));

        assertHttpStatus(failure, 503);

        assertThat(failure.getSuppressed()).hasSize(1);
        assertHttpStatus(failure.getSuppressed()[0], 502);

        assertRequests(operation, true);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldNotTreatInvalidStatusResponseAsPaymentFailure(Operation operation) {

        postStatus = 503;
        postBody = "{}";
        statusResponseBody = """
                {"status":null,"reason":null}
                """;

        Throwable failure = catchThrowable(() -> execute(operation));

        assertHttpStatus(failure, 503);

        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).isInstanceOf(IllegalStateException.class)
                                              .hasMessage("Payment provider returned an invalid response");

        assertRequests(operation, true);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldNotCheckStatusAfterIdempotencyConflict(Operation operation) {

        postStatus = 409;
        postBody = """
                {"message":"Idempotency conflict"}
                """;

        Throwable failure = catchThrowable(() -> execute(operation));

        assertHttpStatus(failure, 409);

        assertRequests(operation, false);
    }

    private PaymentResult execute(Operation operation) {

        return switch (operation) {

        case CHARGE -> gateway.charge(PAYMENT_ID, 123L, 10L, AMOUNT);

        case REFUND -> gateway.refund(PAYMENT_ID, 123L, AMOUNT);
        };
    }

    private void assertRequests(Operation operation, boolean expectStatusRequest) {

        String path = "/api/v1/payments/" + operation.pathSegment();

        String key = operation == Operation.CHARGE ? PAYMENT_ID.toString() : PAYMENT_ID + ":refund";

        ReceivedRequest post = new ReceivedRequest("POST", path, key);

        if (expectStatusRequest) {

            ReceivedRequest status = new ReceivedRequest("GET", path + "/status", key);

            assertThat(requests).containsExactly(post, status);

        } else {
            assertThat(requests).containsExactly(post);
        }
    }

    private void assertHttpStatus(Throwable failure, int expectedStatus) {

        assertThat(failure).isInstanceOf(RestClientResponseException.class);

        RestClientResponseException responseException = (RestClientResponseException) failure;

        assertThat(responseException.getStatusCode().value()).isEqualTo(expectedStatus);
    }

    private void handleRequest(HttpExchange exchange) throws IOException {

        try {
            exchange.getRequestBody().readAllBytes();

            String method = exchange.getRequestMethod();

            requests.add(new ReceivedRequest(method, exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key")));

            if ("POST".equals(method)) {

                if (delayPostResponse) {

                    try {
                        releasePostResponse.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }

                    // Клиент уже мог закрыть соединение после таймаута.
                    return;
                }

                respond(exchange, postStatus, postBody);
                return;
            }

            if ("GET".equals(method)) {
                respond(exchange, statusResponseCode, statusResponseBody);
                return;
            }

            respond(exchange, 405, "{}");

        } finally {
            exchange.close();
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");

        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private record ReceivedRequest(String method, String path, String idempotencyKey) {
    }

    private enum Operation {

        CHARGE("charges"), REFUND("refunds");

        private final String pathSegment;

        Operation(String pathSegment) {
            this.pathSegment = pathSegment;
        }

        String pathSegment() {
            return pathSegment;
        }
    }
}