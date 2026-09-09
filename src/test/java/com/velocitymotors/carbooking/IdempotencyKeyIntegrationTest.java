package com.velocitymotors.carbooking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.dto.BookingResponse;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.enums.VehicleCategory;
import com.velocitymotors.carbooking.repository.BookingRepository;

/**
 * Proves the Idempotency-Key header actually prevents a duplicate booking end to end,
 * over the real HTTP endpoint and real PostgreSQL - not just at the BookingService unit
 * level. Without a key, retrying an identical request for the same vehicle/dates gets a
 * 409 from the vehicle-overlap check; with a key, it gets back the same 201 and the same
 * booking, exactly once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class IdempotencyKeyIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void repeatingTheSameIdempotencyKeyReturnsTheSameBookingInsteadOfAConflict() {
        String idempotencyKey = "test-key-" + System.nanoTime();
        BookingRequest request = new BookingRequest(
                "Idempotent Customer", "VEH90001",
                LocalDate.now().plusDays(5), LocalDate.now().plusDays(7),
                VehicleCategory.SUV, PaymentMode.CASH, null);

        ResponseEntity<BookingResponse> first = postBooking(request, idempotencyKey);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).isNotNull();
        String bookingId = first.getBody().bookingId();

        ResponseEntity<BookingResponse> second = postBooking(request, idempotencyKey);

        // Without the idempotency key, this second call would hit the vehicle-overlap
        // check and come back as a 409 - the whole point is that it doesn't.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().bookingId()).isEqualTo(bookingId);
        assertThat(second.getBody().status()).isEqualTo(first.getBody().status());

        List<com.velocitymotors.carbooking.entity.Booking> matching = bookingRepository.findAll().stream()
                .filter(booking -> booking.getVehicleId().equals("VEH90001"))
                .toList();
        assertThat(matching).hasSize(1);
    }

    @Test
    void twoGenuinelyConcurrentRequestsWithTheSameKeyStillProduceOnlyOneBooking() throws Exception {
        String idempotencyKey = "concurrent-key-" + System.nanoTime();
        BookingRequest request = new BookingRequest(
                "Concurrent Customer", "VEH90002",
                LocalDate.now().plusDays(8), LocalDate.now().plusDays(9),
                VehicleCategory.COMPACT, PaymentMode.CASH, null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<BookingResponse>> firstCall = executor.submit(() -> postBooking(request, idempotencyKey));
            Future<ResponseEntity<BookingResponse>> secondCall = executor.submit(() -> postBooking(request, idempotencyKey));

            ResponseEntity<BookingResponse> first = firstCall.get(10, TimeUnit.SECONDS);
            ResponseEntity<BookingResponse> second = secondCall.get(10, TimeUnit.SECONDS);

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(first.getBody()).isNotNull();
            assertThat(second.getBody()).isNotNull();
            assertThat(second.getBody().bookingId()).isEqualTo(first.getBody().bookingId());

            List<com.velocitymotors.carbooking.entity.Booking> matching = bookingRepository.findAll().stream()
                    .filter(booking -> booking.getVehicleId().equals("VEH90002"))
                    .toList();
            assertThat(matching).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private ResponseEntity<BookingResponse> postBooking(BookingRequest request, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        return restTemplate.postForEntity("/booking", new HttpEntity<>(request, headers), BookingResponse.class);
    }
}
