package com.velocitymotors.carbooking.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class IdempotencyKeyInProgressException extends RuntimeException {
    public IdempotencyKeyInProgressException(String message) {
        super(message);
    }
}
