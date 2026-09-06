package com.velocitymotors.carbooking.payment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.exception.BankTransferWindowExpiredException;

@Component
public class BankTransferPaymentStrategy implements PaymentStrategy {

    private final Clock clock;
    private final long cancellationWindowHours;

    public BankTransferPaymentStrategy(
            Clock clock,
            @Value("${app.booking.cancellation.window-hours}") long cancellationWindowHours) {
        this.clock = clock;
        this.cancellationWindowHours = cancellationWindowHours;
    }

    @Override
    public Set<PaymentMode> supportedModes() {
        return Set.of(PaymentMode.BANK_TRANSFER);
    }

    @Override
    public PaymentResult process(BookingRequest request, String bookingId) {
        LocalDateTime deadline = request.rentalStartDate().atStartOfDay().minusHours(cancellationWindowHours);
        if (!LocalDateTime.now(clock).isBefore(deadline)) {
            throw new BankTransferWindowExpiredException(
                "Bank transfer is not accepted within " + cancellationWindowHours +
                " hours of the rental start date; choose another payment method or a later rental date");
        }
        return new PaymentResult(BookingStatus.PENDING_PAYMENT);
    }
}