package com.velocitymotors.carbooking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.enums.VehicleCategory;

class DigitalWalletPaymentStrategyTest {

    private final DigitalWalletPaymentStrategy strategy = new DigitalWalletPaymentStrategy();

    @Test
    void supportsCashAndDigitalWallet() {
        assertThat(strategy.supportedModes())
                .containsExactlyInAnyOrder(PaymentMode.CASH, PaymentMode.DIGITAL_WALLET);
    }

    @Test
    void confirmsImmediatelyForCash() {
        PaymentResult result = strategy.process(bookingRequest(PaymentMode.CASH), "BKG0000001");

        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void confirmsImmediatelyForDigitalWallet() {
        PaymentResult result = strategy.process(bookingRequest(PaymentMode.DIGITAL_WALLET), "BKG0000001");

        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    private BookingRequest bookingRequest(PaymentMode paymentMode) {
        return new BookingRequest(
                "Test Customer",
                "VEH12345",
                LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 9, 22),
                VehicleCategory.SUV,
                paymentMode,
                null
        );
    }
}
