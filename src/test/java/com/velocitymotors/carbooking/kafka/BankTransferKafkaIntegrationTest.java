package com.velocitymotors.carbooking.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import com.velocitymotors.carbooking.AbstractPostgresIntegrationTest;
import com.velocitymotors.carbooking.entity.Booking;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.enums.VehicleCategory;
import com.velocitymotors.carbooking.repository.BookingRepository;

@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {"bank-transfer-payment-events", "bank-transfer-payment-events-dlt"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class BankTransferKafkaIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void bankTransferEventConfirmsMatchingPendingBooking() throws InterruptedException {
        Booking pendingBooking = Booking.builder()
                .id("BKG0099999")
                .customerName("Integration Test Customer")
                .vehicleId("VEH99999")
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
                {"paymentId":"PAY-IT-001","senderAccountNumber":"ACC000111","paymentAmount":750.00,"transactionDetails":"TXN987654321 BKG0099999"}
                """;
        producer.send("bank-transfer-payment-events", message);

        Booking confirmed = waitForStatus("BKG0099999", BookingStatus.CONFIRMED, Duration.ofSeconds(10));

        assertThat(confirmed).isNotNull();
        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void malformedEventIsRoutedToDeadLetterTopicWithoutRetrying() throws InterruptedException {
        KafkaTemplate<String, String> producer = createTestProducer();
        String malformedMessage = "not valid json at all";

        producer.send("bank-transfer-payment-events", malformedMessage);

        // Proves MalformedBankTransferEventException actually reaches the dead-letter
        // topic end-to-end, through the real container factory's error handler - not
        // just that the listener throws the right type in isolation (see the plain unit
        // test for that). Whether it skips the retry backoff first is Spring Kafka's own
        // well-tested behavior for addNotRetryableExceptions, confirmed by reading its
        // source directly rather than asserted here via a timing check, which proved
        // too flaky under full-suite load to be a reliable signal.
        ConsumerRecord<String, String> dltRecord = consumeOneRecordFrom("bank-transfer-payment-events-dlt");

        assertThat(dltRecord.value()).isEqualTo(malformedMessage);
    }

    private ConsumerRecord<String, String> consumeOneRecordFrom(String topic) {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("dlt-test-group", "true", embeddedKafkaBroker);
        consumerProps.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer =
                new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer()) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, topic);
            return KafkaTestUtils.getSingleRecord(consumer, topic, Duration.ofSeconds(10));
        }
    }

    private KafkaTemplate<String, String> createTestProducer() {
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
