package com.velocitymotors.carbooking.service;


import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.dto.BookingResponse;
import com.velocitymotors.carbooking.entity.Booking;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.exception.InvalidBookingDurationException;
import com.velocitymotors.carbooking.exception.MissingPaymentReferenceException;
import com.velocitymotors.carbooking.repository.BookingRepository;

@Service
public class BookingService {

    private final BookingRepository repository;
    private final BookingIdGenerator idGenerator;
    private final VehicleValidationService vehicleValidationService;

    public BookingService( 
        BookingRepository repository, 
        BookingIdGenerator idGenerator, 
        VehicleValidationService vehicleValidationService) 
    {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.vehicleValidationService = vehicleValidationService;
    }

    public BookingResponse createBooking(BookingRequest request) {
        vehicleValidationService.validate(request.vehicleId());

        validateRentalPeriod(request.rentalStartDate(), request.rentalEndDate());
        validatePaymentReference(request);

        String bookingId = idGenerator.generate();

        Booking booking;
        booking = Booking.builder()
                .id(bookingId)
                .customerName(request.customerName())
                .vehicleId(request.vehicleId())
                .rentalStartDate(request.rentalStartDate())
                .rentalEndDate(request.rentalEndDate())
                .vehicleCategory(request.vehicleCategory())
                .paymentMode(request.paymentMode()) 
                .paymentReference(request.paymentReference())
                .status(BookingStatus.PENDING_PAYMENT)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        repository.save(booking);
        return new BookingResponse(booking.getId(), booking.getStatus());
    }

    private void validateRentalPeriod(LocalDate startDate, LocalDate endDate) {
        
        if (startDate == null || endDate == null || !startDate.isBefore(endDate)) {
            throw new InvalidBookingDurationException("Rental end date must be after the rental start date");
        }

        if (ChronoUnit.DAYS.between(startDate, endDate) > 21) {
            throw new InvalidBookingDurationException("Rental period cannot exceed more than 21 days");
        }
    }

    private void validatePaymentReference(BookingRequest request) {
        
        if(request.paymentMode() == PaymentMode.CREDIT_CARD && (request.paymentReference() == null || request.paymentReference().isBlank()) ) {
            throw new MissingPaymentReferenceException("Payment reference is required for credit card payments");
        }
        
    }

}
