package com.velocitymotors.carbooking.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Generic DEBUG-level entry/exit/timing trace for the service, payment, and client
 * layers - a pure cross-cutting concern with no business meaning of its own, which is
 * exactly what AOP is for (as opposed to the business-specific log.info/log.warn calls
 * inside those methods, which stay as explicit code since they carry meaning an aspect
 * can't infer).
 *
 * Deliberately does NOT log method arguments or return values: several methods in scope
 * take BookingRequest (customer name) or a payment reference, and a generic aspect has
 * no way to know which fields are safe to print. Logging "this method ran, and took Xms"
 * is enough for tracing call flow and performance without undoing the deliberate
 * PII/payment-reference masking done explicitly elsewhere (e.g. CreditCardValidationClientImpl).
 */
@Aspect
@Component
public class MethodTraceLoggingAspect {

    @Around("execution(public * com.velocitymotors.carbooking.service..*(..)) || "
            + "execution(public * com.velocitymotors.carbooking.payment..*(..)) || "
            + "execution(public * com.velocitymotors.carbooking.client..*(..))")
    public Object traceMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        Logger log = LoggerFactory.getLogger(joinPoint.getTarget().getClass());
        String method = joinPoint.getSignature().toShortString();

        if (!log.isDebugEnabled()) {
            return joinPoint.proceed();
        }

        log.debug("Entering {}", method);
        long startNanos = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            log.debug("Exiting {} ({} ms)", method, elapsedMillis(startNanos));
            return result;
        } catch (Throwable ex) {
            log.debug("{} threw {} ({} ms)", method, ex.getClass().getSimpleName(), elapsedMillis(startNanos));
            throw ex;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
