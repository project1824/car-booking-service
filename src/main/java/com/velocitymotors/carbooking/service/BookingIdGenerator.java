package com.velocitymotors.carbooking.service;

import org.springframework.stereotype.Component;

import com.velocitymotors.carbooking.repository.BookingRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Generates a 10-character booking id like "BKG6700417", backed by the postgres sequence
 * booking_id_seq (see V3__create_booking_id_sequence.sql) - safe with any number of app
 * instances running at once. The raw sequence value is scrambled before formatting, so
 * two bookings made back to back don't get near-identical ids - there's no auth on this
 * branch yet (see README known gaps), so a predictable id would make every other
 * customer's booking trivially guessable.
 */
@Slf4j
@Component
public class BookingIdGenerator {

    private static final String PREFIX = "BKG";

    // Scrambles the sequence's 1..9,999,999 range without ever colliding: MODULUS is
    // 10^7 = 2^7 * 5^7, and MULTIPLIER is coprime with it (any odd number not ending in
    // 0 or 5 works), which makes x -> (x * MULTIPLIER) % MODULUS a bijection - every
    // sequence value still maps to exactly one id, just not in the same order it was
    // handed out. This is obfuscation, not cryptographic security - someone who collects
    // enough (sequence value, id) pairs could recover MULTIPLIER with basic algebra.
    // It's meant to stop casual enumeration, not a determined attacker.
    private static final long MODULUS = 10_000_000L;
    private static final long MULTIPLIER = 6_700_417L;

    private final BookingRepository repository;

    public BookingIdGenerator(BookingRepository repository) {
        this.repository = repository;
    }

    public String generate() {
        long next = repository.nextBookingIdSequence();
        long scrambled = (next * MULTIPLIER) % MODULUS;
        String id = PREFIX + String.format("%07d", scrambled);
        log.debug("Generated booking id {} (sequence value {})", id, next);
        return id;
    }

}
