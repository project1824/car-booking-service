package com.velocitymotors.carbooking.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.velocitymotors.carbooking.client.dto.PaymentStatusResponse;
import com.velocitymotors.carbooking.client.openapi.CreditCardValidationContractValidator;
import com.velocitymotors.carbooking.exception.CreditCardServiceUnavailableException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * Proves the actual retry/circuit-breaker behavior configured for the
 * "creditCardValidation" instance in application.yaml: transient failures (5xx,
 * unreachable) are retried and don't count against the circuit breaker's failure rate
 * differently from definitive ones (4xx), which fail on the first attempt and don't
 * count as a circuit-breaker failure at all - see CreditCardTransientFailurePredicate.
 */
class CreditCardValidationClientResilienceTest {

    private MockWebServer server;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CreditCardValidationContractValidator contractValidator =
            new CreditCardValidationContractValidator(meterRegistry);

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private CreditCardValidationClientImpl clientWith(RetryRegistry retryRegistry, CircuitBreakerRegistry cbRegistry)
            throws IOException {
        server = new MockWebServer();
        server.start();
        return new CreditCardValidationClientImpl(RestClient.builder(), server.url("/").toString(), meterRegistry,
                retryRegistry, cbRegistry, contractValidator);
    }

    @Test
    void retriesTransientFailureAndSucceedsOnASubsequentAttempt() throws IOException {
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryOnException(new CreditCardTransientFailurePredicate())
                .build());
        CircuitBreakerRegistry cbRegistry = permissiveCircuitBreakerRegistry();
        CreditCardValidationClientImpl client = clientWith(retryRegistry, cbRegistry);

        server.enqueue(new MockResponse().setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Internal server error\"}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"lastUpdateDate\":\"2026-09-06T10:00:00Z\",\"status\":\"APPROVED\"}"));

        PaymentStatusResponse response = client.checkStatus("DL123456789");

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void doesNotRetryADefinitiveFourHundredFailure() throws IOException {
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryOnException(new CreditCardTransientFailurePredicate())
                .build());
        CircuitBreakerRegistry cbRegistry = permissiveCircuitBreakerRegistry();
        CreditCardValidationClientImpl client = clientWith(retryRegistry, cbRegistry);

        server.enqueue(new MockResponse().setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Payment not found\"}"));

        assertThatThrownBy(() -> client.checkStatus("UNKNOWN"))
                .isInstanceOf(CreditCardServiceUnavailableException.class);

        // Only the first attempt happened - a 4xx is definitive, retrying it would just
        // send the same bad request again.
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void circuitBreakerOpensAfterRepeatedTransientFailuresAndFailsFast() throws IOException {
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordException(new CreditCardTransientFailurePredicate())
                .build();
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(cbConfig);
        CreditCardValidationClientImpl client = clientWith(retryRegistry, cbRegistry);

        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setResponseCode(500)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":\"Internal server error\"}"));
            assertThatThrownBy(() -> client.checkStatus("DL123456789"))
                    .isInstanceOf(CreditCardServiceUnavailableException.class);
        }
        assertThat(server.getRequestCount()).isEqualTo(4);
        assertThat(cbRegistry.circuitBreaker("creditCardValidation").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        // The 5th call must fail fast without going over the network - the mock server's
        // request count must not increase.
        assertThatThrownBy(() -> client.checkStatus("DL123456789"))
                .isInstanceOf(CreditCardServiceUnavailableException.class);
        assertThat(server.getRequestCount()).isEqualTo(4);
        assertThat(meterRegistry.get("credit_card_validation_calls_total")
                .tag("outcome", "circuit_open").counter().count()).isEqualTo(1.0);
    }

    @Test
    void repeated4xxFailuresDoNotCountAgainstTheCircuitBreaker() throws IOException {
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .recordException(new CreditCardTransientFailurePredicate())
                .build();
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(cbConfig);
        CreditCardValidationClientImpl client = clientWith(retryRegistry, cbRegistry);

        for (int i = 0; i < 6; i++) {
            server.enqueue(new MockResponse().setResponseCode(404)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":\"Payment not found\"}"));
            assertThatThrownBy(() -> client.checkStatus("UNKNOWN"))
                    .isInstanceOf(CreditCardServiceUnavailableException.class);
        }

        assertThat(server.getRequestCount()).isEqualTo(6);
        assertThat(cbRegistry.circuitBreaker("creditCardValidation").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private static CircuitBreakerRegistry permissiveCircuitBreakerRegistry() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(100)
                .minimumNumberOfCalls(100)
                .build());
    }
}
