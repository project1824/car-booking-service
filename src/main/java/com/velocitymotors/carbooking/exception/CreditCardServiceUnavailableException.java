package com.velocitymotors.carbooking.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;


@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class CreditCardServiceUnavailableException extends RuntimeException {
    public CreditCardServiceUnavailableException(String message, Throwable cause) { 
        super(message, cause); 

    }
}
