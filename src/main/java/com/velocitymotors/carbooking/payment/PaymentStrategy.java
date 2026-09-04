package com.velocitymotors.carbooking.payment;

import java.util.Set;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.enums.PaymentMode;

public interface PaymentStrategy {

        Set<PaymentMode> supportedModes();
        PaymentResult process(BookingRequest request, String bookingId);
}
