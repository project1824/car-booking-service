package com.velocitymotors.carbooking.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.velocitymotors.carbooking.client.dto.PaymentStatusResponse;
import com.velocitymotors.carbooking.exception.CreditCardServiceUnavailableException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class CreditCardValidationClientImplTest {

    private MockWebServer server;
    private CreditCardValidationClientImpl client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new CreditCardValidationClientImpl(WebClient.builder(), server.url("/").toString());
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

        CreditCardValidationClientImpl unreachableClient =
                new CreditCardValidationClientImpl(WebClient.builder(), deadUrl);

        assertThatThrownBy(() -> unreachableClient.checkStatus("DL123456789"))
                .isInstanceOf(CreditCardServiceUnavailableException.class);
    }
}
