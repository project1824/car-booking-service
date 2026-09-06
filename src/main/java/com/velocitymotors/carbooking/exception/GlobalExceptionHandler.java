package com.velocitymotors.carbooking.exception;

import java.time.Instant;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.velocitymotors.carbooking.dto.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Rejected request failing bean validation: {}", message);
        return ResponseEntity.badRequest().body(new ErrorResponse(message, Instant.now()));
    }

   @ExceptionHandler({InvalidVehicleException.class, InvalidBookingDurationException.class,
        MissingPaymentReferenceException.class, BankTransferWindowExpiredException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex) {
        log.warn("Rejected request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(PaymentDeclinedException.class)
    public ResponseEntity<ErrorResponse> handlePaymentDeclined(PaymentDeclinedException ex) {
        log.warn("Payment declined: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
        .body(new ErrorResponse(ex.getMessage(), Instant.now()));
    }
    @ExceptionHandler(CreditCardServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleServiceUnavailable(CreditCardServiceUnavailableException ex) {
        log.error("Upstream dependency failure: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler({VehicleUnavailableException.class, PaymentReferenceAlreadyUsedException.class})
    public ResponseEntity<ErrorResponse> handleConflict(RuntimeException ex) {
        log.warn("Rejected request due to conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.getMessage(), Instant.now()));
    }

    /**
     * Catches any Spring-internal exception that already carries its own correct HTTP
     * status (e.g. InvalidApiVersionException for an unrecognized X-API-Version) so it
     * isn't swallowed into the generic 500 below - respects whatever status/reason
     * Spring itself attached rather than guessing.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
        log.warn("Rejected request: {}", ex.getReason());
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        return ResponseEntity.status(ex.getStatusCode()).body(new ErrorResponse(message, Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception while processing request", ex);
        return ResponseEntity.internalServerError().body(new ErrorResponse("An unexpected error occurred", Instant.now()));
    }
}
