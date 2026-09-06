
package com.velocitymotors.carbooking.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.velocitymotors.carbooking.entity.Booking;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.repository.BookingRepository;

@Component
public class BookingCancellationScheduler {

    private static final Logger log = LoggerFactory.getLogger(BookingCancellationScheduler.class);

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

        for (Booking booking : pendingBankTransfers) {
            LocalDateTime deadline = booking.getRentalStartDate().atStartOfDay().minusHours(cancellationWindowHours);
            if (!now.isBefore(deadline)) {
                int cancelled = repository.cancelIfPending(booking.getId(), Instant.now(clock));
                if (cancelled > 0) {
                    log.info("Booking {} auto-cancelled: bank transfer not received {}h before rental start",
                            booking.getId(), cancellationWindowHours);
                }
            }
        }
    }
}