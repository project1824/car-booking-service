package com.velocitymotors.carbooking.logging;

import java.util.function.Supplier;

import org.slf4j.MDC;

/**
 * Small helper to tag a block's log lines with a booking id - used by BookingService,
 * BankTransferPaymentEventListener and BookingCancellationScheduler. Kept as a plain
 * helper instead of an aop aspect since the booking id is usually computed partway
 * through the method (generated, parsed, or from a loop var), not available as a
 * parameter at the method boundary where aop would need it.
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
