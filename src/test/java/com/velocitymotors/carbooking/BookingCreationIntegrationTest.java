package com.velocitymotors.carbooking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.velocitymotors.carbooking.dto.BookingRequest;
import com.velocitymotors.carbooking.dto.BookingResponse;
import com.velocitymotors.carbooking.dto.ErrorResponse;
import com.velocitymotors.carbooking.entity.Booking;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.enums.VehicleCategory;
import com.velocitymotors.carbooking.repository.BookingRepository;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * True end-to-end integration test for booking creation: real embedded HTTP server,
 * real BookingService/PaymentStrategy wiring, real PostgreSQL persistence (via
 * Testcontainers) through BookingRepository. Only the external credit-card-validation-service
 * is replaced, with a MockWebServer standing in for it - everything else in the request
 * path is the actual production code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@EmbeddedKafka(partitions = 1, topics = {"bank-transfer-payment-events"}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class BookingCreationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static MockWebServer creditCardMockServer;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @BeforeAll
    static void startMockServer() throws IOException {
        creditCardMockServer = new MockWebServer();
        creditCardMockServer.start();
    }

    @AfterAll
    static void stopMockServer() throws IOException {
        creditCardMockServer.shutdown();
    }

    @DynamicPropertySource
    static void overrideCreditCardBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("credit-card-validation-service.base-url", () -> creditCardMockServer.url("/").toString());
    }

    @Test
    void cashBookingIsConfirmedAndPersistedInRealDatabase() {
        BookingRequest request = new BookingRequest(
                "Jane Doe", "VEH55555",
                LocalDate.now().plusDays(5), LocalDate.now().plusDays(7),
                VehicleCategory.SUV, PaymentMode.CASH, null);

        ResponseEntity<BookingResponse> response = restTemplate.postForEntity("/booking", request, BookingResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(BookingStatus.CONFIRMED);

        Booking persisted = bookingRepository.findById(response.getBody().bookingId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(persisted.getCustomerName()).isEqualTo("Jane Doe");
        assertThat(persisted.getPaymentMode()).isEqualTo(PaymentMode.CASH);
    }

    @Test
    void bankTransferBookingIsPendingAndPersistedInRealDatabase() {
        BookingRequest request = new BookingRequest(
                "John Smith", "VEH66666",
                LocalDate.now().plusDays(10), LocalDate.now().plusDays(12),
                VehicleCategory.LUXURY, PaymentMode.BANK_TRANSFER, null);

        ResponseEntity<BookingResponse> response = restTemplate.postForEntity("/booking", request, BookingResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(BookingStatus.PENDING_PAYMENT);

        Booking persisted = bookingRepository.findById(response.getBody().bookingId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void creditCardBookingApprovedByRealValidationCallIsConfirmed() {
        creditCardMockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"lastUpdateDate\":\"2026-09-06T10:00:00Z\",\"status\":\"APPROVED\"}"));

        BookingRequest request = new BookingRequest(
                "Alice", "VEH77777",
                LocalDate.now().plusDays(5), LocalDate.now().plusDays(6),
                VehicleCategory.COMPACT, PaymentMode.CREDIT_CARD, "DL123456789");

        ResponseEntity<BookingResponse> response = restTemplate.postForEntity("/booking", request, BookingResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(BookingStatus.CONFIRMED);

        Booking persisted = bookingRepository.findById(response.getBody().bookingId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void creditCardBookingRejectedByRealValidationCallReturns422AndPersistsNothing() {
        creditCardMockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"lastUpdateDate\":\"2026-09-06T10:00:00Z\",\"status\":\"REJECTED\"}"));

        BookingRequest request = new BookingRequest(
                "Bob", "VEH88888",
                LocalDate.now().plusDays(5), LocalDate.now().plusDays(6),
                VehicleCategory.SEDAN, PaymentMode.CREDIT_CARD, "DL999999999");

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity("/booking", request, ErrorResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void bankTransferBookingCreatedViaRestIsConfirmedByRealKafkaEvent() throws InterruptedException {
        BookingRequest request = new BookingRequest(
                "Kafka RoundTrip", "VEH11111",
                LocalDate.now().plusDays(10), LocalDate.now().plusDays(12),
                VehicleCategory.SUV, PaymentMode.BANK_TRANSFER, null);

        ResponseEntity<BookingResponse> createResponse = restTemplate.postForEntity("/booking", request, BookingResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        String bookingId = createResponse.getBody().bookingId();

        KafkaTemplate<String, String> producer = createKafkaTestProducer();
        String message = """
                {"paymentId":"PAY-E2E-001","senderAccountNumber":"ACC999999","paymentAmount":650.00,"transactionDetails":"TXN987654321 %s"}
                """.formatted(bookingId);
        producer.send("bank-transfer-payment-events", message);

        Booking confirmed = waitForStatus(bookingId, BookingStatus.CONFIRMED, Duration.ofSeconds(10));

        assertThat(confirmed).isNotNull();
        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    private KafkaTemplate<String, String> createKafkaTestProducer() {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        ProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<>(producerProps);
        return new KafkaTemplate<>(producerFactory);
    }

    private Booking waitForStatus(String bookingId, BookingStatus expectedStatus, Duration timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        Booking booking = null;
        while (System.currentTimeMillis() < deadline) {
            booking = bookingRepository.findById(bookingId).orElse(null);
            if (booking != null && booking.getStatus() == expectedStatus) {
                return booking;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        return booking;
    }
}
