package com.velocitymotors.carbooking.payment;

import java.util.Set;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.enums.PaymentMode;

/**
 * One implementation per payment mode. BookingService builds a paymentMode -> strategy
 * map from every bean found, so adding a new mode later just means adding a new class.
 */
public interface PaymentStrategy {

        /** Which payment modes this strategy handles - a strategy can cover more than one. */
        Set<PaymentMode> supportedModes();

        /** Runs whatever this payment mode needs and returns the booking status it decided on. */
        PaymentResult process(BookingRequest request, String bookingId);
}
