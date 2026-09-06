package com.velocitymotors.carbooking.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.velocitymotors.carbooking.AbstractPostgresIntegrationTest;
import com.velocitymotors.carbooking.entity.Booking;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.enums.VehicleCategory;
import com.velocitymotors.carbooking.repository.BookingRepository;

/**
 * Higher-fidelity twin of {@link BankTransferKafkaIntegrationTest}, running against a real
 * Kafka broker in Docker instead of an in-process embedded one. Excluded from the default
 * build (see the "testcontainers" tag exclusion in pom.xml's surefire config) since it
 * requires Docker to be running - kept opt-in so `mvn test`/`mvn verify` never depend on it.
 *
 * Run explicitly with: mvn test -Dtest=BankTransferKafkaTestcontainersTest -Dexcluded.test.groups=
 */
@Tag("testcontainers")
@Testcontainers
@SpringBootTest
class BankTransferKafkaTestcontainersTest extends AbstractPostgresIntegrationTest {

    @Container
    static KafkaContainer kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void bankTransferEventConfirmsMatchingPendingBooking() throws InterruptedException {
        Booking pendingBooking = Booking.builder()
                .id("BKG0088888")
                .customerName("Testcontainers Customer")
                .vehicleId("VEH88888")
                .rentalStartDate(LocalDate.now().plusDays(10))
                .rentalEndDate(LocalDate.now().plusDays(12))
                .vehicleCategory(VehicleCategory.SUV)
                .paymentMode(PaymentMode.BANK_TRANSFER)
                .status(BookingStatus.PENDING_PAYMENT)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        bookingRepository.save(pendingBooking);

        KafkaTemplate<String, String> producer = createTestProducer();
        String message = """
                {"paymentId":"PAY-TC-001","senderAccountNumber":"ACC000222","paymentAmount":900.00,"transactionDetails":"TXN987654321 BKG0088888"}
                """;
        producer.send("bank-transfer-payment-events", message);

        Booking confirmed = waitForStatus("BKG0088888", BookingStatus.CONFIRMED, Duration.ofSeconds(15));

        assertThat(confirmed).isNotNull();
        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    private KafkaTemplate<String, String> createTestProducer() {
        Map<String, Object> producerProps = Map.of(
                org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class,
                org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class
        );
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
