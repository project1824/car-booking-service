
package com.velocitymotors.carbooking.payment;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.velocitymotors.carbooking.client.CreditCardValidationClient;
import com.velocitymotors.carbooking.client.dto.PaymentStatusResponse;
import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.exception.PaymentDeclinedException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CreditCardPaymentStrategy implements PaymentStrategy{

    private final CreditCardValidationClient client;

    public CreditCardPaymentStrategy(CreditCardValidationClient client) {
        this.client = client;
    }

    @Override
    public Set<PaymentMode> supportedModes() {
       return Set.of(PaymentMode.CREDIT_CARD);
    }

    @Override
    public PaymentResult process(BookingRequest request, String bookingId) {

        PaymentStatusResponse response = client.checkStatus(request.paymentReference());
        if("APPROVED".equalsIgnoreCase(response.status())) {
            log.info("Booking {} confirmed via credit card, validation status={}", bookingId, response.status());
            return new PaymentResult(BookingStatus.CONFIRMED);
        }
        log.warn("Booking {} credit card payment declined, validation status={}", bookingId, response.status());
        throw new PaymentDeclinedException("Credit card payment was not approved, status=" + response.status());
    }


}
