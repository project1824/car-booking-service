package com.velocitymotors.carbooking.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class VehicleUnavailableException extends RuntimeException {
    public VehicleUnavailableException(String message) {
        super(message);
    }
}
