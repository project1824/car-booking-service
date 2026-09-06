package com.velocitymotors.carbooking.logging;

import java.util.function.Supplier;

import org.slf4j.MDC;

/**
 * Small helper for the "tag this block's log lines with a booking id" pattern used by
 * BookingService, BankTransferPaymentEventListener, and BookingCancellationScheduler.
 *
 * This is deliberately a plain utility, not an AOP aspect: in all three call sites the
 * booking id is a value computed partway through the method (generated, parsed out of a
 * message, or read off a loop variable), not an incoming parameter available at the
 * method boundary - which is what AOP needs to intercept declaratively. Extracting those
 * inner blocks into separately-proxyable methods just to make an annotation-driven aspect
 * fit would add more indirection than it removes, so a plain try/finally wrapped once
 * here is the more honest fix for the duplication.
 */
public final class MdcContext {

    private static final String BOOKING_ID_KEY = "bookingId";

    private MdcContext() {
    }

    public static void withBookingId(String bookingId, Runnable action) {
        withBookingId(bookingId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T withBookingId(String bookingId, Supplier<T> action) {
        MDC.put(BOOKING_ID_KEY, bookingId);
        try {
            return action.get();
        } finally {
            MDC.remove(BOOKING_ID_KEY);
        }
    }
}
