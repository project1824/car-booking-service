package com.velocitymotors.carbooking.payment;

import java.util.Set;

import com.velocitymotors.carbooking.enums.PaymentMode;

public interface PaymentStrategy {

        Set<PaymentMode> supportedModes();
        process(BookingRequest request, String bookingId);
}
