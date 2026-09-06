package com.velocitymotors.carbooking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.velocitymotors.carbooking.client.CreditCardValidationClient;
import com.velocitymotors.carbooking.client.dto.PaymentStatusResponse;
import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.enums.VehicleCategory;
import com.velocitymotors.carbooking.exception.PaymentDeclinedException;

@ExtendWith(MockitoExtension.class)
class CreditCardPaymentStrategyTest {

    @Mock
    private CreditCardValidationClient client;

    private CreditCardPaymentStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new CreditCardPaymentStrategy(client);
    }

    @Test
    void confirmsBookingWhenValidationApproved() {
        when(client.checkStatus("DL123456789"))
                .thenReturn(new PaymentStatusResponse("2026-09-06T10:00:00Z", "APPROVED"));

        PaymentResult result = strategy.process(bookingRequest("DL123456789"), "BKG0000001");

        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void throwsWhenValidationRejected() {
        when(client.checkStatus("DL999999999"))
                .thenReturn(new PaymentStatusResponse("2026-09-06T10:00:00Z", "REJECTED"));

        assertThatThrownBy(() -> strategy.process(bookingRequest("DL999999999"), "BKG0000001"))
                .isInstanceOf(PaymentDeclinedException.class);
    }

    private BookingRequest bookingRequest(String paymentReference) {
        return new BookingRequest(
                "Test Customer",
                "VEH12345",
                LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 9, 22),
                VehicleCategory.SUV,
                PaymentMode.CREDIT_CARD,
                paymentReference
        );
    }
}
