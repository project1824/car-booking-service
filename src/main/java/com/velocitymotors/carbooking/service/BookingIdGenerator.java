package com.velocitymotors.carbooking.service;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.velocitymotors.carbooking.repository.BookingRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class BookingIdGenerator {

    private static final String PREFIX = "BKG";
    private final AtomicLong sequence;

    public BookingIdGenerator(BookingRepository repository) {
        this.sequence = new AtomicLong(repository.count());
    }

    public String generate() {
        long next = sequence.incrementAndGet() % 10_000_000L;
        String id = PREFIX + String.format("%07d", next);
        log.debug("Generated booking id {}", id);
        return id;
    }

}
