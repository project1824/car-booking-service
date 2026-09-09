package com.velocitymotors.carbooking.client;

import java.util.function.Predicate;

import com.velocitymotors.carbooking.exception.CreditCardServiceUnavailableException;

/**
 * Checks if a credit card service failure is worth retrying (network issue, 5xx) or not
 * (4xx, same request would just fail again). Resilience4j loads this by name from
 * application.yaml, so it needs the empty constructor.
 */
public class CreditCardTransientFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        return throwable instanceof CreditCardServiceUnavailableException ex && ex.isTransient();
    }
}
