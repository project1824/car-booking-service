package com.velocitymotors.carbooking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.enums.VehicleCategory;
import com.velocitymotors.carbooking.exception.BankTransferWindowExpiredException;

class BankTransferPaymentStrategyTest {

    private static final long WINDOW_HOURS = 48;

    // "Now" fixed at 2026-09-10T10:00:00Z for most tests
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);

    private final BankTransferPaymentStrategy strategy =
            new BankTransferPaymentStrategy(FIXED_CLOCK, WINDOW_HOURS);

    @Test
    void returnsPendingPaymentWhenRentalStartIsWellBeyondWindow() {
        // rentalStartDate 10 days out -> deadline is well after "now"
        BookingRequest request = bookingRequest(LocalDate.of(2026, 9, 20));

        PaymentResult result = strategy.process(request, "BKG0000001");

        assertThat(result.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void throwsWhenRentalStartIsAlreadyInsideTheWindow() {
        // rental starts tomorrow -> deadline (tomorrow midnight - 48h) is already in the past
        BookingRequest request = bookingRequest(LocalDate.of(2026, 9, 11));

        assertThatThrownBy(() -> strategy.process(request, "BKG0000001"))
                .isInstanceOf(BankTransferWindowExpiredException.class);
    }

    @Test
    void throwsWhenExactlyAtDeadline() {
        // now fixed at exactly midnight; rentalStartDate two days later means
        // deadline (rentalStartDate midnight - 48h) lands exactly on "now"
        Clock midnightClock = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC);
        BankTransferPaymentStrategy midnightStrategy = new BankTransferPaymentStrategy(midnightClock, WINDOW_HOURS);
        BookingRequest request = bookingRequest(LocalDate.of(2026, 9, 12));

        assertThatThrownBy(() -> midnightStrategy.process(request, "BKG0000001"))
                .isInstanceOf(BankTransferWindowExpiredException.class);
    }

    private BookingRequest bookingRequest(LocalDate rentalStartDate) {
        return new BookingRequest(
                "Test Customer",
                "VEH12345",
                rentalStartDate,
                rentalStartDate.plusDays(2),
                VehicleCategory.SUV,
                PaymentMode.BANK_TRANSFER,
                null
        );
    }
}
