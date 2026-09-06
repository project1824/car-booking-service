
package com.velocitymotors.carbooking.client;

import com.velocitymotors.carbooking.client.dto.PaymentStatusResponse;

public interface CreditCardValidationClient {
    PaymentStatusResponse checkStatus(String paymentReference);
}
