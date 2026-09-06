
package com.velocitymotors.carbooking.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.velocitymotors.carbooking.client.dto.PaymentStatusResponse;
import com.velocitymotors.carbooking.client.dto.PaymentStatusRequest;
import com.velocitymotors.carbooking.exception.CreditCardServiceUnavailableException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CreditCardValidationClientImpl implements CreditCardValidationClient {

    private final WebClient webClient;

    public CreditCardValidationClientImpl(
            WebClient.Builder webClientBuilder,
            @Value("${credit-card-validation-service.base-url}") String baseUrl) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public PaymentStatusResponse checkStatus(String paymentReference) {
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
            return response;
        } catch (WebClientResponseException ex) {
            log.error("credit-card-validation-service returned an error for reference={}: {}",
                    mask(paymentReference), ex.getStatusCode(), ex);
            throw new CreditCardServiceUnavailableException(
                    "credit-card-validation-service returned an error: " + ex.getStatusCode(), ex);
        } catch (WebClientRequestException ex) {
            log.error("Unable to reach credit-card-validation-service for reference={}", mask(paymentReference), ex);
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
