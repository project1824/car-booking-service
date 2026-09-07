package com.velocitymotors.carbooking.exception;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;


@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class CreditCardServiceUnavailableException extends RuntimeException {

    /**
     * The upstream's own HTTP status, when this was caused by an error response
     * (e.g. 500). Null when caused by a pure connectivity failure (timeout, connection
     * refused). Used by the retry/circuit-breaker predicate to tell a transient upstream
     * fault (worth retrying) apart from a definitive client-side fault like a 4xx
     * (retrying won't change the outcome, so it shouldn't cost extra attempts or count
     * against the circuit breaker's failure rate).
     */
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
