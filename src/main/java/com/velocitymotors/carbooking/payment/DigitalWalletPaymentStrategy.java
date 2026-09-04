package com.velocitymotors.carbooking.payment;
 
import java.util.Set;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;



public class DigitalWalletPaymentStrategy implements PaymentStrategy{

    @Override
    public Set<PaymentMode> supportedModes() {
        return Set.of(PaymentMode.CASH, PaymentMode.DIGITAL_WALLET);
    }

    @Override
    public PaymentResult process(BookingRequest request, String bookingId) {
        return new PaymentResult(BookingStatus.CONFIRMED);
    }
}
