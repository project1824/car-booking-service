package com.velocitymotors.carbooking.kafka;

/**
 * Thrown for a bank-transfer-payment-events message that can never be processed
 * regardless of how many times it's retried (unparseable JSON, or transactionDetails
 * too short to carry a booking ID) - as opposed to a transient failure like a
 * momentarily unreachable database, which genuinely is worth retrying.
 * Registered as non-retryable on the listener container's error handler (see
 * KafkaConsumerConfig) so it skips straight to the dead-letter topic instead of
 * retrying an outcome that can't change.
 */
public class MalformedBankTransferEventException extends RuntimeException {

    public MalformedBankTransferEventException(String message) {
        super(message);
    }

    public MalformedBankTransferEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
