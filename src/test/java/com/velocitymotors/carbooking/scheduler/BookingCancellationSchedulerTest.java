package com.velocitymotors.carbooking.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.velocitymotors.carbooking.entity.Booking;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.repository.BookingRepository;

@ExtendWith(MockitoExtension.class)
class BookingCancellationSchedulerTest {

    private static final long WINDOW_HOURS = 48;

    @Mock
    private BookingRepository repository;

    @Test
    void cancelsBookingWhenDeadlineHasPassed() {
        // now = 2026-09-10T10:00:00Z; booking starts tomorrow -> deadline already passed
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);
        BookingCancellationScheduler scheduler = new BookingCancellationScheduler(repository, clock, WINDOW_HOURS);

        Booking expiredBooking = pendingBankTransferBooking("BKG0000001", LocalDate.of(2026, 9, 11));
        when(repository.findByPaymentModeAndStatus(PaymentMode.BANK_TRANSFER, BookingStatus.PENDING_PAYMENT))
                .thenReturn(List.of(expiredBooking));
        when(repository.cancelIfPending(eq("BKG0000001"), any(Instant.class))).thenReturn(1);

        scheduler.cancelExpiredBankTransferBookings();

        verify(repository).cancelIfPending(eq("BKG0000001"), any(Instant.class));
    }

    @Test
    void doesNotCancelBookingWellWithinTheWindow() {
        // now = 2026-09-10T10:00:00Z; booking starts in 10 days -> deadline far in the future
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);
        BookingCancellationScheduler scheduler = new BookingCancellationScheduler(repository, clock, WINDOW_HOURS);

        Booking safeBooking = pendingBankTransferBooking("BKG0000002", LocalDate.of(2026, 9, 20));
        when(repository.findByPaymentModeAndStatus(PaymentMode.BANK_TRANSFER, BookingStatus.PENDING_PAYMENT))
                .thenReturn(List.of(safeBooking));

        scheduler.cancelExpiredBankTransferBookings();

        verify(repository, never()).cancelIfPending(any(), any());
    }

    @Test
    void attemptsCancelEvenWhenRepositoryReportsAlreadyResolved() {
        // Simulates a race with the Kafka listener: findBy... still saw PENDING_PAYMENT,
        // but by the time cancelIfPending runs, the booking was already confirmed elsewhere.
        // The conditional update returning 0 rows is the mechanism that prevents the race
        // from corrupting the booking's status.
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);
        BookingCancellationScheduler scheduler = new BookingCancellationScheduler(repository, clock, WINDOW_HOURS);

        Booking racedBooking = pendingBankTransferBooking("BKG0000003", LocalDate.of(2026, 9, 11));
        when(repository.findByPaymentModeAndStatus(PaymentMode.BANK_TRANSFER, BookingStatus.PENDING_PAYMENT))
                .thenReturn(List.of(racedBooking));
        when(repository.cancelIfPending(eq("BKG0000003"), any(Instant.class))).thenReturn(0);

        scheduler.cancelExpiredBankTransferBookings();

        verify(repository).cancelIfPending(eq("BKG0000003"), any(Instant.class));
    }

    private Booking pendingBankTransferBooking(String id, LocalDate rentalStartDate) {
        return Booking.builder()
                .id(id)
                .customerName("Test Customer")
                .vehicleId("VEH12345")
                .rentalStartDate(rentalStartDate)
                .rentalEndDate(rentalStartDate.plusDays(2))
                .paymentMode(PaymentMode.BANK_TRANSFER)
                .status(BookingStatus.PENDING_PAYMENT)
                .build();
    }
}
