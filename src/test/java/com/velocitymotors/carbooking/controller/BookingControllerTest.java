package com.velocitymotors.carbooking.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.dto.BookingResponse;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.exception.CreditCardServiceUnavailableException;
import com.velocitymotors.carbooking.exception.InvalidVehicleException;
import com.velocitymotors.carbooking.exception.PaymentDeclinedException;
import com.velocitymotors.carbooking.service.BookingService;

@WebMvcTest(BookingController.class)
class BookingControllerTest {

    private static final String VALID_REQUEST_JSON = """
            {
              "customerName": "Test Customer",
              "vehicleId": "VEH12345",
              "rentalStartDate": "2026-09-20",
              "rentalEndDate": "2026-09-22",
              "vehicleCategory": "SUV",
              "paymentMode": "CASH",
              "paymentReference": ""
            }
            """;

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String TEST_IDEMPOTENCY_KEY = "test-idempotency-key";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingService bookingService;

    @Test
    void returnsCreatedWithBookingResponseOnSuccess() throws Exception {
        when(bookingService.createBooking(any(BookingRequest.class), any()))
                .thenReturn(new BookingResponse("BKG0000001", BookingStatus.CONFIRMED));

        mockMvc.perform(post("/booking")
                        .header(IDEMPOTENCY_KEY_HEADER, TEST_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value("BKG0000001"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void returnsBadRequestWhenCustomerNameBlank() throws Exception {
        String invalidJson = """
                {
                  "customerName": "",
                  "vehicleId": "VEH12345",
                  "rentalStartDate": "2026-09-20",
                  "rentalEndDate": "2026-09-22",
                  "vehicleCategory": "SUV",
                  "paymentMode": "CASH",
                  "paymentReference": ""
                }
                """;

        mockMvc.perform(post("/booking")
                        .header(IDEMPOTENCY_KEY_HEADER, TEST_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("customerName")));

        verifyNoInteractions(bookingService);
    }

    @Test
    void returnsBadRequestWhenIdempotencyKeyHeaderMissing() throws Exception {
        mockMvc.perform(post("/booking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bookingService);
    }

    @Test
    void returnsBadRequestWhenServiceThrowsInvalidVehicleException() throws Exception {
        when(bookingService.createBooking(any(BookingRequest.class), any()))
                .thenThrow(new InvalidVehicleException("Vehicle ID is invalid: BAD"));

        mockMvc.perform(post("/booking")
                        .header(IDEMPOTENCY_KEY_HEADER, TEST_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Vehicle ID is invalid: BAD"));
    }

    @Test
    void returnsUnprocessableContentWhenServiceThrowsPaymentDeclinedException() throws Exception {
        when(bookingService.createBooking(any(BookingRequest.class), any()))
                .thenThrow(new PaymentDeclinedException("Credit card payment was not approved, status=REJECTED"));

        mockMvc.perform(post("/booking")
                        .header(IDEMPOTENCY_KEY_HEADER, TEST_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().is(422));
    }

    @Test
    void returnsBadGatewayWhenServiceThrowsCreditCardServiceUnavailableException() throws Exception {
        when(bookingService.createBooking(any(BookingRequest.class), any()))
                .thenThrow(new CreditCardServiceUnavailableException(
                        "Unable to reach credit-card-validation-service", new RuntimeException("boom")));

        mockMvc.perform(post("/booking")
                        .header(IDEMPOTENCY_KEY_HEADER, TEST_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isBadGateway());
    }
}
