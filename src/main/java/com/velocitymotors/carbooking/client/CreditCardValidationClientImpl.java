
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
    private final CreditCardValidationContractValidator contractValidator;
    // Deliberately not a Spring-managed bean: this app's autoconfigured ObjectMapper is
    // Jackson 3's tools.jackson.databind.ObjectMapper (see JacksonAutoConfiguration in
    // spring-boot-jackson). openapi4j (and the contract-check code around it) is a Jackson-2
    // library, needing this com.fasterxml.jackson.databind.ObjectMapper instead - a
    // completely different, unrelated class despite the similar name. No bean of this type
    // exists in this app's context, so it's self-instantiated here, same as
    // CreditCardValidationContractValidator already does.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CreditCardValidationClientImpl(
            RestClient.Builder restClientBuilder,
            @Value("${credit-card-validation-service.base-url}") String baseUrl,
            MeterRegistry meterRegistry,
            RetryRegistry retryRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            CreditCardValidationContractValidator contractValidator) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.meterRegistry = meterRegistry;
        this.retry = retryRegistry.retry("creditCardValidation");
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("creditCardValidation");
        this.contractValidator = contractValidator;
    }

    @Override
    public PaymentStatusResponse checkStatus(String paymentReference) {
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

    private String writeJson(PaymentStatusRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            // Can't happen for this simple single-field record, but if it ever does, contract
            // validation should just skip this call rather than break the actual payment flow.
            log.warn("Failed to serialize credit-card-validation-service request for contract validation", ex);
            return "{}";
        }
    }

    private PaymentStatusResponse readJson(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, PaymentStatusResponse.class);
        } catch (JsonProcessingException ex) {
            throw new CreditCardServiceUnavailableException(
                    "credit-card-validation-service returned unparseable JSON", ex);
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
