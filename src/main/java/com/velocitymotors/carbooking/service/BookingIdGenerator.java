package com.velocitymotors.carbooking.service;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.velocitymotors.carbooking.repository.BookingRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Generates a 10-character booking id like "BKG0000123". Known limitation: the counter
 * lives in memory per pod, seeded from repository.count() at startup - with more than
 * one pod running, two pods can generate the same id. A real deployment needs a db
 * sequence or a shared id generator instead.
 */
@Slf4j
@Component
public class BookingIdGenerator {

    private static final String PREFIX = "BKG";
    private final AtomicLong sequence;

    public BookingIdGenerator(BookingRepository repository) {
        this.sequence = new AtomicLong(repository.count());
    }

    /** Wraps around after 9,999,999 so the id always fits in 7 digits after the prefix. */
    public String generate() {
        long next = sequence.incrementAndGet() % 10_000_000L;
        String id = PREFIX + String.format("%07d", next);
        log.debug("Generated booking id {}", id);
        return id;
    }

}
