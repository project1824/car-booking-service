package com.velocitymotors.carbooking.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
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
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Proves the bulkhead actually caps concurrent calls, not just that it's configured.
 * Two calls are held open at once (blocked in the mock server, simulating a slow
 * upstream) to fill both permits, then a third call is made while those two are still
 * in flight - it must be rejected immediately, without ever reaching the network.
 */
class CreditCardValidationClientBulkheadTest {

    private MockWebServer server;
    private ExecutorService executor;

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void thirdConcurrentCallIsRejectedWhileTwoAreStillInFlight() throws Exception {
        CountDownLatch requestsReceived = new CountDownLatch(2);
        CountDownLatch releaseResponses = new CountDownLatch(1);

        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                requestsReceived.countDown();
                releaseResponses.await();
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"lastUpdateDate\":\"2026-09-06T10:00:00Z\",\"status\":\"APPROVED\"}");
            }
        });
        server.start();

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom().slidingWindowSize(100).minimumNumberOfCalls(100).build());
        BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(
                BulkheadConfig.custom().maxConcurrentCalls(2).maxWaitDuration(Duration.ZERO).build());
        CreditCardValidationContractValidator contractValidator = new CreditCardValidationContractValidator(meterRegistry);

        CreditCardValidationClientImpl client = new CreditCardValidationClientImpl(RestClient.builder(),
                server.url("/").toString(), meterRegistry, retryRegistry, circuitBreakerRegistry, bulkheadRegistry,
                contractValidator);

        executor = Executors.newFixedThreadPool(2);
        Future<PaymentStatusResponse> first = executor.submit(() -> client.checkStatus("DL111111111"));
        Future<PaymentStatusResponse> second = executor.submit(() -> client.checkStatus("DL222222222"));

        // Both permits are taken once the mock server has actually received both requests.
        assertThat(requestsReceived.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.checkStatus("DL333333333"))
                .isInstanceOf(CreditCardServiceUnavailableException.class);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // Rejected by the bulkhead, not by a slow network call - must return near-instantly
        // and never reach the mock server at all.
        assertThat(elapsedMillis).isLessThan(500);
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(meterRegistry.get("credit_card_validation_calls_total")
                .tag("outcome", "bulkhead_full").counter().count()).isEqualTo(1.0);

        releaseResponses.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo("APPROVED");
        assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo("APPROVED");
    }
}
