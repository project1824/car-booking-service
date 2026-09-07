package com.velocitymotors.carbooking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.dto.BookingResponse;
import com.velocitymotors.carbooking.dto.ErrorResponse;
import com.velocitymotors.carbooking.dto.LoginRequest;
import com.velocitymotors.carbooking.dto.LoginResponse;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.enums.VehicleCategory;

/**
 * True end-to-end proof that /booking is actually protected, over real HTTP - not just
 * that a directly-generated test token happens to work (the other integration tests use
 * authHeaders() for that, since their focus is booking/scheduling behavior, not auth
 * itself). This test drives the real /auth/login endpoint too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AuthenticationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void bookingWithoutAuthorizationHeaderIsRejected() {
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/booking", validBookingRequest(), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bookingWithGarbageBearerTokenIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-a-real-jwt");
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/booking", HttpMethod.POST, new HttpEntity<>(validBookingRequest(), headers), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithWrongPasswordIsRejected() {
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/auth/login", new LoginRequest("demo", "wrong-password"), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void realLoginTokenIsAcceptedByTheBookingEndpoint() {
        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity(
                "/auth/login", new LoginRequest("demo", "demo-password"), LoginResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        String token = loginResponse.getBody().token();
        assertThat(token).isNotBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<BookingResponse> bookingResponse = restTemplate.exchange(
                "/booking", HttpMethod.POST, new HttpEntity<>(validBookingRequest(), headers), BookingResponse.class);

        assertThat(bookingResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private BookingRequest validBookingRequest() {
        return new BookingRequest(
                "Auth Test Customer", "VEH99911",
                LocalDate.now().plusDays(5), LocalDate.now().plusDays(7),
                VehicleCategory.SUV, PaymentMode.CASH, null);
    }
}
