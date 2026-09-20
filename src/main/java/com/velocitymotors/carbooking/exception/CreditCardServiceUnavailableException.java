package com.velocitymotors.carbooking.exception;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;


@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class CreditCardServiceUnavailableException extends RuntimeException {

    // the upstream's actual http status, when we got one (e.g. 500). null if it was a pure
    // connection failure. used to tell a transient failure (worth retrying) apart from a
    // real 4xx (retrying won't help).
    private final HttpStatusCode upstreamStatus;

    public CreditCardServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
        this.upstreamStatus = null;
    }

    public CreditCardServiceUnavailableException(String message, Throwable cause, HttpStatusCode upstreamStatus) {
        super(message, cause);
        this.upstreamStatus = upstreamStatus;
    }

    public HttpStatusCode getUpstreamStatus() {
        return upstreamStatus;
    }

    /** Worth retrying: either no upstream response at all, or the upstream itself failed (5xx). */
    public boolean isTransient() {
        return upstreamStatus == null || upstreamStatus.is5xxServerError();
    }
}
