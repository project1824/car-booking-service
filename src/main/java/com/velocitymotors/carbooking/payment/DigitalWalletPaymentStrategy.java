package com.velocitymotors.carbooking.payment;
 
import java.util.Set;

import org.springframework.stereotype.Component;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;

@Component
public class DigitalWalletPaymentStrategy implements PaymentStrategy {

    @Override
    public Set<PaymentMode> supportedModes() {
        return Set.of(PaymentMode.CASH, PaymentMode.DIGITAL_WALLET);
    }

    @Override
    public PaymentResult process(BookingRequest request, String bookingId) {
        return new PaymentResult(BookingStatus.CONFIRMED);
    }
}
