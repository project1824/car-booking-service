package com.velocitymotors.carbooking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.dto.BookingResponse;
import com.velocitymotors.carbooking.entity.Booking;
import com.velocitymotors.carbooking.entity.IdempotencyKey;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.enums.VehicleCategory;
import com.velocitymotors.carbooking.exception.IdempotencyKeyInProgressException;
import com.velocitymotors.carbooking.exception.InvalidBookingDurationException;
import com.velocitymotors.carbooking.exception.InvalidVehicleException;
import com.velocitymotors.carbooking.exception.MissingPaymentReferenceException;
import com.velocitymotors.carbooking.exception.PaymentReferenceAlreadyUsedException;
import com.velocitymotors.carbooking.exception.VehicleUnavailableException;
import com.velocitymotors.carbooking.payment.PaymentResult;
import com.velocitymotors.carbooking.payment.PaymentStrategy;
import com.velocitymotors.carbooking.repository.BookingRepository;
import com.velocitymotors.carbooking.repository.IdempotencyKeyRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository repository;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private BookingIdGenerator idGenerator;

    @Mock
    private VehicleValidationService vehicleValidationService;

    @Mock
    private PaymentStrategy strategy;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        when(strategy.supportedModes())
                .thenReturn(Set.of(PaymentMode.CASH, PaymentMode.CREDIT_CARD, PaymentMode.BANK_TRANSFER));
        bookingService = new BookingService(
                repository, idempotencyKeyRepository, idGenerator, vehicleValidationService, List.of(strategy), meterRegistry);
    }

    @Test
    void createsAndSavesBookingUsingResolvedStrategyResult() {
        when(idGenerator.generate()).thenReturn("BKG0000001");
        when(strategy.process(any(BookingRequest.class), eq("BKG0000001")))
                .thenReturn(new PaymentResult(BookingStatus.CONFIRMED));

        BookingRequest request = bookingRequest(PaymentMode.CASH, null);

        BookingResponse response = bookingService.createBooking(request);

        assertThat(response.bookingId()).isEqualTo("BKG0000001");
        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        verify(vehicleValidationService).validate("VEH12345");

        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        verify(repository).save(bookingCaptor.capture());
        Booking saved = bookingCaptor.getValue();
        assertThat(saved.getId()).isEqualTo("BKG0000001");
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(saved.getCustomerName()).isEqualTo("Test Customer");
        assertThat(saved.getPaymentMode()).isEqualTo(PaymentMode.CASH);

        assertThat(meterRegistry.get("bookings_total")
                .tag("paymentMode", "CASH")
                .tag("status", "CONFIRMED")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void propagatesVehicleValidationFailureWithoutSaving() {
        doThrow(new InvalidVehicleException("bad vehicle"))
                .when(vehicleValidationService).validate("VEH12345");

        BookingRequest request = bookingRequest(PaymentMode.CASH, null);

        assertThatThrownBy(() -> bookingService.createBooking(request))
                .isInstanceOf(InvalidVehicleException.class);

        verify(repository, never()).save(any());
        verify(idGenerator, never()).generate();
    }

    @Test
    void rejectsRentalPeriodLongerThan21DaysWithoutSaving() {
        BookingRequest request = new BookingRequest(
                "Test Customer", "VEH12345",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 25),
                VehicleCategory.SUV, PaymentMode.CASH, null);

        assertThatThrownBy(() -> bookingService.createBooking(request))
                .isInstanceOf(InvalidBookingDurationException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsEndDateNotAfterStartDateWithoutSaving() {
        BookingRequest request = new BookingRequest(
                "Test Customer", "VEH12345",
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10),
                VehicleCategory.SUV, PaymentMode.CASH, null);

        assertThatThrownBy(() -> bookingService.createBooking(request))
                .isInstanceOf(InvalidBookingDurationException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsCreditCardBookingWithoutPaymentReference() {
        BookingRequest request = bookingRequest(PaymentMode.CREDIT_CARD, "");

        assertThatThrownBy(() -> bookingService.createBooking(request))
                .isInstanceOf(MissingPaymentReferenceException.class);

        verify(repository, never()).save(any());
        verify(idGenerator, never()).generate();
    }

    @Test
    void throwsWhenNoStrategyRegisteredForPaymentMode() {
        PaymentStrategy limitedStrategy = mock(PaymentStrategy.class);
        when(limitedStrategy.supportedModes()).thenReturn(Set.of(PaymentMode.CASH));
        BookingService limitedService = new BookingService(
                repository, idempotencyKeyRepository, idGenerator, vehicleValidationService, List.of(limitedStrategy), meterRegistry);

        when(idGenerator.generate()).thenReturn("BKG0000001");
        BookingRequest request = bookingRequest(PaymentMode.CREDIT_CARD, "DL123456789");

        assertThatThrownBy(() -> limitedService.createBooking(request))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsBookingWhenVehicleHasOverlappingActiveBooking() {
        when(repository.existsOverlappingActiveBooking("VEH12345",
                LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 22)))
                .thenReturn(true);

        BookingRequest request = bookingRequest(PaymentMode.CASH, null);

        assertThatThrownBy(() -> bookingService.createBooking(request))
                .isInstanceOf(VehicleUnavailableException.class);

        verify(repository).lockVehicle("VEH12345");
        verify(repository, never()).save(any());
        verify(idGenerator, never()).generate();
    }

    @Test
    void rejectsCreditCardBookingWhenPaymentReferenceAlreadyConfirmedElsewhere() {
        when(repository.existsByPaymentReferenceAndStatus("DL123456789", BookingStatus.CONFIRMED))
                .thenReturn(true);

        BookingRequest request = bookingRequest(PaymentMode.CREDIT_CARD, "DL123456789");

        assertThatThrownBy(() -> bookingService.createBooking(request))
                .isInstanceOf(PaymentReferenceAlreadyUsedException.class);

        verify(repository).lockPaymentReference("DL123456789");
        verify(repository, never()).save(any());
        verify(idGenerator, never()).generate();
    }

    @Test
    void doesNotCheckPaymentReferenceReuseForNonCreditCardModes() {
        when(idGenerator.generate()).thenReturn("BKG0000001");
        when(strategy.process(any(BookingRequest.class), eq("BKG0000001")))
                .thenReturn(new PaymentResult(BookingStatus.CONFIRMED));

        BookingRequest request = bookingRequest(PaymentMode.CASH, null);

        bookingService.createBooking(request);

        verify(repository, never()).lockPaymentReference(any());
        verify(repository, never()).existsByPaymentReferenceAndStatus(any(), any());
    }

    @Test
    void skipsIdempotencyKeyHandlingWhenNoKeyProvided() {
        when(idGenerator.generate()).thenReturn("BKG0000001");
        when(strategy.process(any(BookingRequest.class), eq("BKG0000001")))
                .thenReturn(new PaymentResult(BookingStatus.CONFIRMED));

        bookingService.createBooking(bookingRequest(PaymentMode.CASH, null));

        verifyNoInteractions(idempotencyKeyRepository);
    }

    @Test
    void completesIdempotencyClaimAfterSuccessfulBooking() {
        when(idempotencyKeyRepository.tryClaim(eq("key-1"), any(Instant.class))).thenReturn(1);
        when(idGenerator.generate()).thenReturn("BKG0000001");
        when(strategy.process(any(BookingRequest.class), eq("BKG0000001")))
                .thenReturn(new PaymentResult(BookingStatus.CONFIRMED));

        BookingResponse response = bookingService.createBooking(bookingRequest(PaymentMode.CASH, null), "key-1");

        assertThat(response.bookingId()).isEqualTo("BKG0000001");
        verify(idempotencyKeyRepository).completeClaim("key-1", "BKG0000001", BookingStatus.CONFIRMED);
    }

    @Test
    void returnsExistingResultWithoutReprocessingWhenIdempotencyKeyAlreadyClaimed() {
        when(idempotencyKeyRepository.tryClaim(eq("key-1"), any(Instant.class))).thenReturn(0);
        when(idempotencyKeyRepository.findById("key-1")).thenReturn(Optional.of(IdempotencyKey.builder()
                .key("key-1")
                .bookingId("BKG0000099")
                .status(BookingStatus.CONFIRMED)
                .createdAt(Instant.now())
                .build()));

        BookingResponse response = bookingService.createBooking(bookingRequest(PaymentMode.CASH, null), "key-1");

        assertThat(response.bookingId()).isEqualTo("BKG0000099");
        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        verifyNoInteractions(vehicleValidationService, idGenerator);
        verify(strategy, never()).process(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void throwsWhenIdempotencyKeyIsStillBeingProcessed() {
        when(idempotencyKeyRepository.tryClaim(eq("key-1"), any(Instant.class))).thenReturn(0);
        when(idempotencyKeyRepository.findById("key-1")).thenReturn(Optional.of(IdempotencyKey.builder()
                .key("key-1")
                .createdAt(Instant.now())
                .build()));

        assertThatThrownBy(() -> bookingService.createBooking(bookingRequest(PaymentMode.CASH, null), "key-1"))
                .isInstanceOf(IdempotencyKeyInProgressException.class);

        verifyNoInteractions(vehicleValidationService, idGenerator);
        verify(strategy, never()).process(any(), any());
        verify(repository, never()).save(any());
    }

    private BookingRequest bookingRequest(PaymentMode paymentMode, String paymentReference) {
        return new BookingRequest(
                "Test Customer",
                "VEH12345",
                LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 9, 22),
                VehicleCategory.SUV,
                paymentMode,
                paymentReference
        );
    }
}
