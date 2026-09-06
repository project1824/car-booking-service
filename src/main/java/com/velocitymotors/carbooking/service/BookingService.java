package com.velocitymotors.carbooking.service;


import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.dto.BookingResponse;
import com.velocitymotors.carbooking.entity.Booking;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.exception.InvalidBookingDurationException;
import com.velocitymotors.carbooking.exception.MissingPaymentReferenceException;
import com.velocitymotors.carbooking.payment.PaymentResult;
import com.velocitymotors.carbooking.payment.PaymentStrategy;
import com.velocitymotors.carbooking.repository.BookingRepository;

@Service
public class BookingService {

    private final BookingRepository repository;
    private final BookingIdGenerator idGenerator;
    private final VehicleValidationService vehicleValidationService;
    private final Map<PaymentMode, PaymentStrategy> strategies;

    public BookingService( 
        BookingRepository repository, 
        BookingIdGenerator idGenerator, 
        VehicleValidationService vehicleValidationService,
        List<PaymentStrategy> paymentStrategies)
    {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.vehicleValidationService = vehicleValidationService;
        this.strategies = paymentStrategies.stream()
                            .flatMap(strategy -> strategy.supportedModes().stream()
                                .map(mode -> Map.entry(mode, strategy)))
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public BookingResponse createBooking(BookingRequest request) {
        vehicleValidationService.validate(request.vehicleId());

        validateRentalPeriod(request.rentalStartDate(), request.rentalEndDate());
        validatePaymentReference(request);

        String bookingId = idGenerator.generate();

        PaymentStrategy strategy = strategies.get(request.paymentMode());
        if (strategy == null) {
            throw new IllegalStateException("No PaymentStrategy registered for payment mode: " + request.paymentMode());
        }
        PaymentResult result = strategy.process(request, bookingId);
    

        Booking booking = Booking.builder()
                .id(bookingId)
                .customerName(request.customerName())
                .vehicleId(request.vehicleId())
                .rentalStartDate(request.rentalStartDate())
                .rentalEndDate(request.rentalEndDate())
                .vehicleCategory(request.vehicleCategory())
                .paymentMode(request.paymentMode()) 
                .paymentReference(request.paymentReference())
                .status(result.status())
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
