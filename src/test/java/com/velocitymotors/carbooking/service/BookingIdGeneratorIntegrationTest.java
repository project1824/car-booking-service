package com.velocitymotors.carbooking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.velocitymotors.carbooking.AbstractPostgresIntegrationTest;

/**
 * Proves BookingIdGenerator is actually safe under real concurrency, over the real
 * postgres sequence (V3__create_booking_id_sequence.sql) - not just that it compiles.
 * This is exactly the scenario the old in-memory AtomicLong couldn't guarantee once more
 * than one app instance was involved: many callers asking for an id at the same time.
 */
@SpringBootTest
class BookingIdGeneratorIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private BookingIdGenerator bookingIdGenerator;

    @Test
    void concurrentCallsNeverProduceTheSameId() throws Exception {
        int callers = 50;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        try {
            List<Callable<String>> tasks = IntStream.range(0, callers)
                    .<Callable<String>>mapToObj(i -> bookingIdGenerator::generate)
                    .toList();

            List<Future<String>> futures = executor.invokeAll(tasks);
            List<String> ids = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    })
                    .collect(Collectors.toList());

            assertThat(ids).hasSize(callers);
            assertThat(ids).doesNotHaveDuplicates();
            assertThat(ids).allSatisfy(id -> assertThat(id).hasSize(10).startsWith("BKG"));
        } finally {
            executor.shutdownNow();
        }
    }
}
