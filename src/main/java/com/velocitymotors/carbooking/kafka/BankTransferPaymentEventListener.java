package com.velocitymotors.carbooking.kafka;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.velocitymotors.carbooking.repository.BookingRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class BankTransferPaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(BankTransferPaymentEventListener.class);
    private static final int MIN_TRANSACTION_DETAILS_LENGTH = 23;
    private static final int BOOKING_ID_LENGTH = 10;

    private final BookingRepository repository;
    private final ObjectMapper objectMapper;

    public BankTransferPaymentEventListener(BookingRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
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
            log.warn("Ignoring unparseable bank-transfer-payment-event: {}", message, ex);
            return;
        }

        String details = event.transactionDetails();
        String trimmed = details == null ? "" : details.strip();

        if (trimmed.length() < MIN_TRANSACTION_DETAILS_LENGTH) {
            log.warn("Ignoring malformed bank-transfer-payment-event, transactionDetails='{}'", details);
            return;
        }

        String bookingId = trimmed.substring(trimmed.length() - BOOKING_ID_LENGTH);

        int updated = repository.confirmIfPending(bookingId, Instant.now());
        if (updated == 0) {
            log.warn("No PENDING_PAYMENT booking found for bookingId={} (paymentId={})", bookingId, event.paymentId());
        } else {
            log.info("Booking {} confirmed via bank transfer payment {}", bookingId, event.paymentId());
        }
    }
}