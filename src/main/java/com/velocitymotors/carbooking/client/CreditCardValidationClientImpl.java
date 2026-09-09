
package com.velocitymotors.carbooking.client;

import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitymotors.carbooking.client.dto.PaymentStatusRequest;
import com.velocitymotors.carbooking.client.dto.PaymentStatusResponse;
import com.velocitymotors.carbooking.client.openapi.CreditCardValidationContractValidator;
import com.velocitymotors.carbooking.exception.CreditCardServiceUnavailableException;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CreditCardValidationClientImpl implements CreditCardValidationClient {

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final CreditCardValidationContractValidator contractValidator;
    // not a spring bean on purpose - spring's own ObjectMapper here is jackson 3
    // (tools.jackson...), but openapi4j needs the older jackson 2 ObjectMapper. same class
    // name, different package, no bean of this type exists in this app.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CreditCardValidationClientImpl(
            RestClient.Builder restClientBuilder,
            @Value("${credit-card-validation-service.base-url}") String baseUrl,
            MeterRegistry meterRegistry,
            RetryRegistry retryRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry,
            CreditCardValidationContractValidator contractValidator) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.meterRegistry = meterRegistry;
        this.retry = retryRegistry.retry("creditCardValidation");
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("creditCardValidation");
        this.bulkhead = bulkheadRegistry.bulkhead("creditCardValidation");
        this.contractValidator = contractValidator;
    }

    /**
     * Wraps callUpstream with bulkhead + retry + circuit breaker, in that order from
     * outside in: bulkhead caps how many calls (across all retries) can be in flight at
     * once, retry sits outside circuit breaker so once the breaker opens partway through
     * a retry the rest fail fast instead of hitting the network again. See
     * application.yaml for the actual thresholds.
     */
    @Override
    public PaymentStatusResponse checkStatus(String paymentReference) {
        Supplier<PaymentStatusResponse> decorated = Bulkhead.decorateSupplier(bulkhead,
                Retry.decorateSupplier(retry,
                        CircuitBreaker.decorateSupplier(circuitBreaker, () -> callUpstream(paymentReference))));
        try {
            return decorated.get();
        } catch (CallNotPermittedException ex) {
            log.warn("credit-card-validation-service circuit breaker is open; failing fast for reference={}",
                    mask(paymentReference));
            meterRegistry.counter("credit_card_validation_calls_total", "outcome", "circuit_open").increment();
            throw new CreditCardServiceUnavailableException(
                    "credit-card-validation-service is temporarily unavailable (circuit open)", ex);
        } catch (BulkheadFullException ex) {
            log.warn("credit-card-validation-service bulkhead is full; failing fast for reference={}",
                    mask(paymentReference));
            meterRegistry.counter("credit_card_validation_calls_total", "outcome", "bulkhead_full").increment();
            throw new CreditCardServiceUnavailableException(
                    "credit-card-validation-service is temporarily unavailable (too many concurrent calls)", ex);
        }
    }

    /**
     * Makes the real call. Fetches the response as a raw string (not straight into
     * PaymentStatusResponse) so contractValidator can check the actual json against the
     * openapi spec, both for what we send and what comes back, before it's parsed.
     */
    private PaymentStatusResponse callUpstream(String paymentReference) {
        log.debug("Calling credit-card-validation-service for reference={}", mask(paymentReference));
        PaymentStatusRequest requestBody = new PaymentStatusRequest(paymentReference);
        contractValidator.validateRequest(writeJson(requestBody));
        try {
            ResponseEntity<String> httpResponse = restClient.post()
                    .uri("/payment-status")
                    .body(requestBody)
                    .retrieve()
                    .toEntity(String.class);
            String rawBody = httpResponse.getBody();
            contractValidator.validateResponse(httpResponse.getStatusCode().value(), rawBody);

            PaymentStatusResponse response = readJson(rawBody);
            log.info("credit-card-validation-service responded with status={} for reference={}",
                    response == null ? null : response.status(), mask(paymentReference));
            meterRegistry.counter("credit_card_validation_calls_total", "outcome", "success").increment();
            return response;
        } catch (RestClientResponseException ex) {
            contractValidator.validateResponse(ex.getStatusCode().value(), ex.getResponseBodyAsString());
            log.error("credit-card-validation-service returned an error for reference={}: {}",
                    mask(paymentReference), ex.getStatusCode(), ex);
            meterRegistry.counter("credit_card_validation_calls_total", "outcome", "error").increment();
            throw new CreditCardServiceUnavailableException(
                    "credit-card-validation-service returned an error: " + ex.getStatusCode(), ex, ex.getStatusCode());
        } catch (ResourceAccessException ex) {
            log.error("Unable to reach credit-card-validation-service for reference={}", mask(paymentReference), ex);
            meterRegistry.counter("credit_card_validation_calls_total", "outcome", "error").increment();
            throw new CreditCardServiceUnavailableException(
                    "Unable to reach credit-card-validation-service", ex);
        }
    }

    /** Turns the request into raw json so contractValidator can check it before it's sent. */
    private String writeJson(PaymentStatusRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            // won't happen for this simple record, but if it does, just skip the contract
            // check instead of breaking the actual payment call.
            log.warn("Failed to serialize credit-card-validation-service request for contract validation", ex);
            return "{}";
        }
    }

    /** Parses the raw response body, after contractValidator has already checked it. */
    private PaymentStatusResponse readJson(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, PaymentStatusResponse.class);
        } catch (JsonProcessingException ex) {
            throw new CreditCardServiceUnavailableException(
                    "credit-card-validation-service returned unparseable JSON", ex);
        }
    }

    /** Never log a full payment reference - just enough to spot it in a support ticket. */
    private static String mask(String paymentReference) {
        if (paymentReference == null || paymentReference.length() <= 4) {
            return "****";
        }
        return "****" + paymentReference.substring(paymentReference.length() - 4);
    }
}
