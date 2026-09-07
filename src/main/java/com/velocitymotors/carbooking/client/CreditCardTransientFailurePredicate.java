package com.velocitymotors.carbooking.client;

import java.util.function.Predicate;

import com.velocitymotors.carbooking.exception.CreditCardServiceUnavailableException;

/**
 * Tells apart a transient credit-card-validation-service failure (network blip, upstream
 * 5xx - worth retrying) from a definitive one (upstream 4xx - retrying sends the same bad
 * request again and won't change the outcome). Referenced by name from application.yaml
 * for both the retry and circuit-breaker instances, so only transient failures burn a
 * retry attempt or count against the circuit breaker's failure rate; a 4xx fails fast on
 * the first attempt. Instantiated by Resilience4j via reflection, hence the no-arg
 * constructor requirement satisfied implicitly here.
 */
public class CreditCardTransientFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        return throwable instanceof CreditCardServiceUnavailableException ex && ex.isTransient();
    }
}
