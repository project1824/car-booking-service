package com.velocitymotors.carbooking.kafka;

/**
 * For a bank transfer event that can never succeed no matter how many times it retries
 * (bad json, or transactionDetails too short). Registered as non-retryable in
 * KafkaConsumerConfig so it goes straight to the dead letter topic instead.
 */
public class MalformedBankTransferEventException extends RuntimeException {

    public MalformedBankTransferEventException(String message) {
        super(message);
    }

    public MalformedBankTransferEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
