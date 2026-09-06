
package com.velocitymotors.carbooking.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.velocitymotors.carbooking.entity.Booking;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.logging.MdcContext;
import com.velocitymotors.carbooking.repository.BookingRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class BookingCancellationScheduler {

    private final BookingRepository repository;
    private final Clock clock;
    private final long cancellationWindowHours;

    public BookingCancellationScheduler(
            BookingRepository repository,
            Clock clock,
            @Value("${app.booking.cancellation.window-hours}") long cancellationWindowHours) {
        this.repository = repository;
        this.clock = clock;
        this.cancellationWindowHours = cancellationWindowHours;
    }

    @Scheduled(fixedDelayString = "${app.booking.cancellation.check-interval-ms}")
    @Transactional
    public void cancelExpiredBankTransferBookings() {
        LocalDateTime now = LocalDateTime.now(clock);

        List<Booking> pendingBankTransfers =
                repository.findByPaymentModeAndStatus(PaymentMode.BANK_TRANSFER, BookingStatus.PENDING_PAYMENT);
        log.debug("Cancellation sweep found {} pending bank-transfer booking(s) to evaluate", pendingBankTransfers.size());

        for (Booking booking : pendingBankTransfers) {
            LocalDateTime deadline = booking.getRentalStartDate().atStartOfDay().minusHours(cancellationWindowHours);
            if (!now.isBefore(deadline)) {
                MdcContext.withBookingId(booking.getId(), () -> {
                    int cancelled = repository.cancelIfPending(booking.getId(), Instant.now(clock));
                    if (cancelled > 0) {
                        log.info("Booking {} auto-cancelled: bank transfer not received {}h before rental start",
                                booking.getId(), cancellationWindowHours);
                    }
                });
            }
        }
    }
}
