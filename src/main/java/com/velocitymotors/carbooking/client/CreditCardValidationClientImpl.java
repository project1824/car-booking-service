
package com.velocitymotors.carbooking.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.velocitymotors.carbooking.client.dto.PaymentStatusResponse;
import com.velocitymotors.carbooking.client.dto.PaymentStatusRequest;
import com.velocitymotors.carbooking.exception.CreditCardServiceUnavailableException;


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
        try {
            return webClient.post()
                    .uri("/payment-status")
                    .bodyValue(new PaymentStatusRequest(paymentReference))
                    .retrieve()
                    .bodyToMono(PaymentStatusResponse.class)
                    .block();
        } catch (WebClientResponseException ex) {
            throw new CreditCardServiceUnavailableException(
                    "credit-card-validation-service returned an error: " + ex.getStatusCode(), ex);
        } catch (WebClientRequestException ex) {
            throw new CreditCardServiceUnavailableException(
                    "Unable to reach credit-card-validation-service", ex);
        }
    }
}