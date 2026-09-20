
package com.velocitymotors.carbooking.payment;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.velocitymotors.carbooking.client.CreditCardValidationClient;
import com.velocitymotors.carbooking.client.dto.PaymentStatusResponse;
import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.exception.PaymentDeclinedException;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CreditCardPaymentStrategy implements PaymentStrategy{

    private final CreditCardValidationClient client;
    private final MeterRegistry meterRegistry;

    public CreditCardPaymentStrategy(CreditCardValidationClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Set<PaymentMode> supportedModes() {
       return Set.of(PaymentMode.CREDIT_CARD);
    }

    /** Calls the credit card service and confirms the booking only on a literal "APPROVED". */
    @Override
    public PaymentResult process(BookingRequest request, String bookingId) {

        PaymentStatusResponse response = client.checkStatus(request.paymentReference());
        if("APPROVED".equalsIgnoreCase(response.status())) {
            log.info("Booking {} confirmed via credit card, validation status={}", bookingId, response.status());
            meterRegistry.counter("credit_card_payment_result_total", "result", "approved").increment();
            return new PaymentResult(BookingStatus.CONFIRMED);
        }
        log.warn("Booking {} credit card payment declined, validation status={}", bookingId, response.status());
        meterRegistry.counter("credit_card_payment_result_total", "result", "declined").increment();
        throw new PaymentDeclinedException("Credit card payment was not approved, status=" + response.status());
    }


}
