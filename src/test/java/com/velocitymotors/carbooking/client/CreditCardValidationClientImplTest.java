package com.velocitymotors.carbooking.client;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.velocitymotors.carbooking.client.dto.PaymentStatusResponse;
import com.velocitymotors.carbooking.client.openapi.CreditCardValidationContractValidator;
import com.velocitymotors.carbooking.exception.CreditCardServiceUnavailableException;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Exercises the raw request/response mapping only - retry and circuit-breaker are
 * disabled here (maxAttempts=1, a circuit breaker that never has enough calls to open)
 * so each scenario makes exactly one HTTP call, same as before those were added. Their
 * actual behavior (retrying transient failures, opening after repeated failures) is
 * covered separately in {@link CreditCardValidationClientResilienceTest}.
 */
class CreditCardValidationClientImplTest {

    private MockWebServer server;
    private CreditCardValidationClientImpl client;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom().slidingWindowSize(100).minimumNumberOfCalls(100).build());
    private final BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(
            BulkheadConfig.custom().maxConcurrentCalls(100).build());
    private final CreditCardValidationContractValidator contractValidator =
            new CreditCardValidationContractValidator(meterRegistry);

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new CreditCardValidationClientImpl(RestClient.builder(), server.url("/").toString(), meterRegistry,
                retryRegistry, circuitBreakerRegistry, bulkheadRegistry, contractValidator);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void returnsApprovedStatusOnSuccessfulResponse() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"lastUpdateDate":"2026-09-06T10:00:00Z","status":"APPROVED"}
                        """));

        PaymentStatusResponse response = client.checkStatus("DL123456789");

        assertThat(response.status()).isEqualTo("APPROVED");

        RecordedRequest recordedRequest = server.takeRequest();
        assertThat(recordedRequest.getPath()).isEqualTo("/payment-status");
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getBody().readUtf8()).contains("\"paymentReference\":\"DL123456789\"");
    }

    @Test
    void returnsRejectedStatusWithoutThrowing() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"lastUpdateDate":"2026-09-06T10:00:00Z","status":"REJECTED"}
                        """));

        PaymentStatusResponse response = client.checkStatus("DL999999999");

        assertThat(response.status()).isEqualTo("REJECTED");
    }

    @Test
    void throwsServiceUnavailableOn404PaymentNotFound() {
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Payment not found\"}"));

        assertThatThrownBy(() -> client.checkStatus("UNKNOWN"))
                .isInstanceOf(CreditCardServiceUnavailableException.class);
    }

    @Test
    void throwsServiceUnavailableOn500InternalServerError() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Internal server error\"}"));

        assertThatThrownBy(() -> client.checkStatus("DL123456789"))
                .isInstanceOf(CreditCardServiceUnavailableException.class);
    }

    @Test
    void throwsServiceUnavailableWhenServerUnreachable() throws IOException {
        MockWebServer deadServer = new MockWebServer();
        deadServer.start();
        String deadUrl = deadServer.url("/").toString();
        deadServer.shutdown();

        CreditCardValidationClientImpl unreachableClient = new CreditCardValidationClientImpl(RestClient.builder(),
                deadUrl, meterRegistry, retryRegistry, circuitBreakerRegistry, bulkheadRegistry, contractValidator);

        assertThatThrownBy(() -> unreachableClient.checkStatus("DL123456789"))
                .isInstanceOf(CreditCardServiceUnavailableException.class);
    }
}
