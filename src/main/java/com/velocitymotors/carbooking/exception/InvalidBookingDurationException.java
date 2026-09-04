package com.velocitymotors.carbooking.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidBookingDurationException extends RuntimeException {
    public InvalidBookingDurationException(String message) { 
        super(message); 
    }
}
