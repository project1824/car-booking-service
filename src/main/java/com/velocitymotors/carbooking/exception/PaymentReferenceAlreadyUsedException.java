package com.velocitymotors.carbooking.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class PaymentReferenceAlreadyUsedException extends RuntimeException {
    public PaymentReferenceAlreadyUsedException(String message) {
        super(message);
    }
}
