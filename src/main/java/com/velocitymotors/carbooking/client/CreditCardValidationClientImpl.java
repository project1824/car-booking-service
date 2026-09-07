
package com.velocitymotors.carbooking.client;

import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;

import com.velocitymotors.carbooking.client.dto.PaymentStatusResponse;
import com.velocitymotors.carbooking.client.dto.PaymentStatusRequest;
import com.velocitymotors.carbooking.exception.CreditCardServiceUnavailableException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CreditCardValidationClientImpl implements CreditCardValidationClient {

    private final WebClient webClient;
    private final MeterRegistry meterRegistry;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public CreditCardValidationClientImpl(
            WebClient.Builder webClientBuilder,
            @Value("${credit-card-validation-service.base-url}") String baseUrl,
            MeterRegistry meterRegistry,
            RetryRegistry retryRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.meterRegistry = meterRegistry;
        this.retry = retryRegistry.retry("creditCardValidation");
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("creditCardValidation");
    }

    @Override
    public PaymentStatusResponse checkStatus(String paymentReference) {
        // Retry wraps the circuit breaker (not the other way around) so that once the
        // breaker opens partway through a retry sequence, the remaining attempts fail
        // fast via CallNotPermittedException instead of hammering a struggling upstream -
        // the standard Resilience4j composition for "retry transient failures, but stop
        // retrying once the breaker says the dependency is down."
        Supplier<PaymentStatusResponse> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, () -> callUpstream(paymentReference)));
        try {
            return decorated.get();
        } catch (CallNotPermittedException ex) {
            log.warn("credit-card-validation-service circuit breaker is open; failing fast for reference={}",
                    mask(paymentReference));
            meterRegistry.counter("credit_card_validation_calls_total", "outcome", "circuit_open").increment();
            throw new CreditCardServiceUnavailableException(
                    "credit-card-validation-service is temporarily unavailable (circuit open)", ex);
        }
    }

    private PaymentStatusResponse callUpstream(String paymentReference) {
        log.debug("Calling credit-card-validation-service for reference={}", mask(paymentReference));
        try {
            PaymentStatusResponse response = webClient.post()
                    .uri("/payment-status")
                    .bodyValue(new PaymentStatusRequest(paymentReference))
                    .retrieve()
                    .bodyToMono(PaymentStatusResponse.class)
                    .block();
            log.info("credit-card-validation-service responded with status={} for reference={}",
                    response == null ? null : response.status(), mask(paymentReference));
            meterRegistry.counter("credit_card_validation_calls_total", "outcome", "success").increment();
            return response;
        } catch (WebClientResponseException ex) {
            log.error("credit-card-validation-service returned an error for reference={}: {}",
                    mask(paymentReference), ex.getStatusCode(), ex);
            meterRegistry.counter("credit_card_validation_calls_total", "outcome", "error").increment();
            throw new CreditCardServiceUnavailableException(
                    "credit-card-validation-service returned an error: " + ex.getStatusCode(), ex, ex.getStatusCode());
        } catch (WebClientRequestException ex) {
            log.error("Unable to reach credit-card-validation-service for reference={}", mask(paymentReference), ex);
            meterRegistry.counter("credit_card_validation_calls_total", "outcome", "error").increment();
            throw new CreditCardServiceUnavailableException(
                    "Unable to reach credit-card-validation-service", ex);
        }
    }

    /** Never log a payment reference in full - only enough of it to spot in a support ticket. */
    private static String mask(String paymentReference) {
        if (paymentReference == null || paymentReference.length() <= 4) {
            return "****";
        }
        return "****" + paymentReference.substring(paymentReference.length() - 4);
    }
}
