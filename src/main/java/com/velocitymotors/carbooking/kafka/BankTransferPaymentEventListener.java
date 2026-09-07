package com.velocitymotors.carbooking.kafka;

import java.time.Instant;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.velocitymotors.carbooking.logging.MdcContext;
import com.velocitymotors.carbooking.repository.BookingRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class BankTransferPaymentEventListener {

    private static final int MIN_TRANSACTION_DETAILS_LENGTH = 23;
    private static final int BOOKING_ID_LENGTH = 10;

    private final BookingRepository repository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public BankTransferPaymentEventListener(
            BookingRepository repository, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
        topics = "${app.kafka.topics.bank-transfer-payment-events}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onBankTransferPaymentEvent(String message) {
        BankTransferPaymentEvent event;
        try {
            event = objectMapper.readValue(message, BankTransferPaymentEvent.class);
        } catch (JacksonException ex) {
            log.warn("Unparseable bank-transfer-payment-event, routing to dead-letter topic: {}", message, ex);
            meterRegistry.counter("bank_transfer_events_total", "outcome", "unparseable").increment();
            throw new MalformedBankTransferEventException("Unparseable bank-transfer-payment-event", ex);
        }
        log.debug("Parsed bank-transfer-payment-event paymentId={}", event.paymentId());

        String details = event.transactionDetails();
        String trimmed = details == null ? "" : details.strip();

        if (trimmed.length() < MIN_TRANSACTION_DETAILS_LENGTH) {
            log.warn("Malformed bank-transfer-payment-event, routing to dead-letter topic, transactionDetails='{}'", details);
            meterRegistry.counter("bank_transfer_events_total", "outcome", "malformed").increment();
            throw new MalformedBankTransferEventException(
                    "transactionDetails too short to carry a booking id: '" + details + "'");
        }

        String bookingId = trimmed.substring(trimmed.length() - BOOKING_ID_LENGTH);

        MdcContext.withBookingId(bookingId, () -> {
            int updated = repository.confirmIfPending(bookingId, Instant.now());
            if (updated == 0) {
                log.warn("No PENDING_PAYMENT booking found for bookingId={} (paymentId={})", bookingId, event.paymentId());
                meterRegistry.counter("bank_transfer_events_total", "outcome", "no_matching_booking").increment();
            } else {
                log.info("Booking {} confirmed via bank transfer payment {}", bookingId, event.paymentId());
                meterRegistry.counter("bank_transfer_events_total", "outcome", "confirmed").increment();
            }
        });
    }
}
