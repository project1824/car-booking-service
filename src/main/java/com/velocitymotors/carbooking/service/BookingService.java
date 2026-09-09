package com.velocitymotors.carbooking.service;


import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.dto.BookingResponse;
import com.velocitymotors.carbooking.entity.Booking;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.exception.InvalidBookingDurationException;
import com.velocitymotors.carbooking.exception.MissingPaymentReferenceException;
import com.velocitymotors.carbooking.exception.PaymentReferenceAlreadyUsedException;
import com.velocitymotors.carbooking.exception.VehicleUnavailableException;
import com.velocitymotors.carbooking.logging.MdcContext;
import com.velocitymotors.carbooking.payment.PaymentResult;
import com.velocitymotors.carbooking.payment.PaymentStrategy;
import com.velocitymotors.carbooking.repository.BookingRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class BookingService {

    private final BookingRepository repository;
    private final BookingIdGenerator idGenerator;
    private final VehicleValidationService vehicleValidationService;
    private final Map<PaymentMode, PaymentStrategy> strategies;
    private final MeterRegistry meterRegistry;

    /** Builds the paymentMode -> strategy map from every PaymentStrategy bean spring finds. */
    public BookingService(
        BookingRepository repository,
        BookingIdGenerator idGenerator,
        VehicleValidationService vehicleValidationService,
        List<PaymentStrategy> paymentStrategies,
        MeterRegistry meterRegistry)
    {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.vehicleValidationService = vehicleValidationService;
        this.strategies = paymentStrategies.stream()
                            .flatMap(strategy -> strategy.supportedModes().stream()
                                .map(mode -> Map.entry(mode, strategy)))
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        this.meterRegistry = meterRegistry;
    }

    /**
     * Validates the request, resolves the right PaymentStrategy for the payment mode,
     * and saves the booking with whatever status that strategy decides.
     *
     * Note: credit card bookings make a blocking http call inside strategy.process()
     * below, and since this whole method is one transaction, the db connection +
     * advisory locks stay held for that call. The alternative (insert pending first,
     * update after, like bank transfer does) would change credit card from "confirmed
     * in one response" to "confirmed later" - too big a change for what this needs.
     * Known tradeoff.
     */
    @Transactional
    public BookingResponse createBooking(BookingRequest request) {
        vehicleValidationService.validate(request.vehicleId());

        validateRentalPeriod(request.rentalStartDate(), request.rentalEndDate());
        validatePaymentReference(request);
        log.debug("Validation passed for vehicleId={}, proceeding to payment processing", request.vehicleId());

        checkVehicleAvailable(request);
        if (request.paymentMode() == PaymentMode.CREDIT_CARD) {
            checkPaymentReferenceNotReused(request.paymentReference());
        }

        String bookingId = idGenerator.generate();
        return MdcContext.withBookingId(bookingId, () -> {
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
            log.info("Booking {} persisted with status={}", bookingId, booking.getStatus());
            // named "bookings_total" not "bookings_created_total" - prometheus strips a
            // trailing "_created" from counter names anyway, so this just keeps the name honest.
            meterRegistry.counter("bookings_total",
                    "paymentMode", request.paymentMode().name(),
                    "status", booking.getStatus().name()
            ).increment();
            return new BookingResponse(booking.getId(), booking.getStatus());
        });
    }

    /**
     * Locks this vehicle (a postgres advisory lock, released when the transaction ends)
     * before checking for an overlapping booking, so two requests for the same vehicle
     * can't both pass the check before either one commits.
     */
    private void checkVehicleAvailable(BookingRequest request) {
        repository.lockVehicle(request.vehicleId());
        if (repository.existsOverlappingActiveBooking(
                request.vehicleId(), request.rentalStartDate(), request.rentalEndDate())) {
            log.warn("Vehicle {} unavailable: overlapping active booking for {} to {}",
                    request.vehicleId(), request.rentalStartDate(), request.rentalEndDate());
            throw new VehicleUnavailableException(
                    "Vehicle " + request.vehicleId() + " is already booked for an overlapping period");
        }
    }

    /**
     * The credit card service only tells us if a reference is approved, not if we've
     * already used it for another booking - so we check that ourselves here. Same lock
     * trick as checkVehicleAvailable, just keyed by the reference instead of vehicle id.
     */
    private void checkPaymentReferenceNotReused(String paymentReference) {
        repository.lockPaymentReference(paymentReference);
        if (repository.existsByPaymentReferenceAndStatus(paymentReference, BookingStatus.CONFIRMED)) {
            log.warn("Payment reference already used by a confirmed booking");
            throw new PaymentReferenceAlreadyUsedException(
                    "This payment reference has already been used to confirm a booking");
        }
    }

    /** End date must be after start date, and the rental can't be longer than 21 days. */
    private void validateRentalPeriod(LocalDate startDate, LocalDate endDate) {

        if (startDate == null || endDate == null || !startDate.isBefore(endDate)) {
            throw new InvalidBookingDurationException("Rental end date must be after the rental start date");
        }

        long days = ChronoUnit.DAYS.between(startDate, endDate);
        if (days > 21) {
            throw new InvalidBookingDurationException("Rental period cannot exceed more than 21 days");
        }
        log.debug("Rental period validated: {} day(s), from {} to {}", days, startDate, endDate);
    }

    /** Only credit card needs a payment reference - the other modes don't use it. */
    private void validatePaymentReference(BookingRequest request) {

        if(request.paymentMode() == PaymentMode.CREDIT_CARD && (request.paymentReference() == null || request.paymentReference().isBlank()) ) {
            throw new MissingPaymentReferenceException("Payment reference is required for credit card payments");
        }

    }

}
