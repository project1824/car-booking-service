package com.velocitymotors.carbooking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.dto.BookingResponse;
import com.velocitymotors.carbooking.entity.Booking;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.enums.VehicleCategory;
import com.velocitymotors.carbooking.repository.BookingRepository;
import com.velocitymotors.carbooking.scheduler.BookingCancellationScheduler;

/**
 * True end-to-end test of the "create via the real REST API, then get auto-cancelled by
 * the real scheduler" story. Kept in its own Spring context (separate from
 * BookingCreationIntegrationTest) because it overrides the shared Clock bean and advances
 * it mid-test - sharing that mutated clock with other tests via a cached context would
 * silently skew their notion of "now" too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class BookingSchedulerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private BookingCancellationScheduler bookingCancellationScheduler;

    @Autowired
    private MutableClock mutableClock;

    @Test
    void bankTransferBookingCreatedViaRestIsAutoCancelledByRealScheduler() {
        BookingRequest request = new BookingRequest(
                "Scheduler RoundTrip", "VEH22222",
                LocalDate.now().plusDays(10), LocalDate.now().plusDays(12),
                VehicleCategory.SUV, PaymentMode.BANK_TRANSFER, null);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "scheduler-test-key-" + System.nanoTime());
        ResponseEntity<BookingResponse> createResponse =
                restTemplate.postForEntity("/booking", new HttpEntity<>(request, headers), BookingResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        String bookingId = createResponse.getBody().bookingId();

        // Fast-forward the app's notion of "now" well past this booking's 48h cancellation
        // deadline, instead of waiting real time - the scheduler bean itself is still real.
        mutableClock.advanceBy(Duration.ofDays(9));

        bookingCancellationScheduler.cancelExpiredBankTransferBookings();

        Booking cancelled = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.now(), ZoneOffset.UTC);
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        MutableClock(Instant initial, ZoneId zone) {
            this.instant = new AtomicReference<>(initial);
            this.zone = zone;
        }

        void advanceBy(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant.get(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
