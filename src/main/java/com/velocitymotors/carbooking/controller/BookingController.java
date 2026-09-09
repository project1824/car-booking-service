
package com.velocitymotors.carbooking.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.dto.BookingResponse;
import com.velocitymotors.carbooking.service.BookingService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/booking")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * Idempotency-Key is mandatory, not just supported: this endpoint calls an external
     * payment service, and an opt-in safety net protects nobody who forgets to opt in -
     * exactly the caller most likely to blindly retry after a timeout. A missing header
     * fails with a clean 400 (see GlobalExceptionHandler) rather than silently skipping
     * the protection.
     */
    @PostMapping(version = "1.0")
    public ResponseEntity<BookingResponse> confirmBooking(
            @Valid @RequestBody BookingRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        log.info("Received booking request: vehicleId={}, vehicleCategory={}, paymentMode={}, rentalStartDate={}, rentalEndDate={}",
                request.vehicleId(), request.vehicleCategory(), request.paymentMode(),
                request.rentalStartDate(), request.rentalEndDate());

        BookingResponse response = bookingService.createBooking(request, idempotencyKey);

        log.info("Booking request completed: bookingId={}, status={}", response.bookingId(), response.status());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

}
