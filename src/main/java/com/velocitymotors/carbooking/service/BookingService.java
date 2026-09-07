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
     * NOTE on transaction scope: for CREDIT_CARD bookings, strategy.process() below makes
     * a synchronous, blocking HTTP call to the external validation service - and since
     * this whole method is one transaction, the DB connection (and the advisory locks
     * acquired below) stay held for the duration of that external call. Holding a
     * transaction open across a network call is normally something to avoid, but the
     * alternative - inserting a placeholder row first, calling out, then updating the
     * outcome (the same pattern already used for bank transfer) - would change the
     * credit-card contract from "confirmed synchronously in one response" to "pending
     * until confirmed asynchronously", which is a bigger behavioral change than this
     * service's realistic scale justifies. Documented tradeoff, not an oversight.
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
            // Named "bookings_total" (not "bookings_created_total"): Prometheus/OpenMetrics
            // treats a trailing "_created" as a reserved suffix (used for counter-creation
            // timestamps) and strips it, so "bookings_created_total" is silently exported as
            // "bookings_total" anyway. Naming it that way directly keeps the metric name honest.
            meterRegistry.counter("bookings_total",
                    "paymentMode", request.paymentMode().name(),
                    "status", booking.getStatus().name()
            ).increment();
            return new BookingResponse(booking.getId(), booking.getStatus());
        });
    }

    /**
     * Serializes concurrent booking attempts for the same vehicle via a Postgres
     * advisory lock (released automatically when this transaction ends), then checks
     * for an overlapping active booking. The lock closes the race a plain check-then-
     * insert would otherwise have: without it, two concurrent requests for the same
     * vehicle/dates could both pass this check before either one commits.
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
     * The credit-card-validation-service only answers "is this reference approved?" -
     * it has no notion of a reference already having confirmed a different booking.
     * Without this check, the same paymentReference could confirm two separate bookings
     * off what is really one underlying card transaction. Same advisory-lock treatment
     * as checkVehicleAvailable, keyed by the reference instead of the vehicle id.
     */
    private void checkPaymentReferenceNotReused(String paymentReference) {
        repository.lockPaymentReference(paymentReference);
        if (repository.existsByPaymentReferenceAndStatus(paymentReference, BookingStatus.CONFIRMED)) {
            log.warn("Payment reference already used by a confirmed booking");
            throw new PaymentReferenceAlreadyUsedException(
                    "This payment reference has already been used to confirm a booking");
        }
    }

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

    private void validatePaymentReference(BookingRequest request) {

        if(request.paymentMode() == PaymentMode.CREDIT_CARD && (request.paymentReference() == null || request.paymentReference().isBlank()) ) {
            throw new MissingPaymentReferenceException("Payment reference is required for credit card payments");
        }

    }

}
