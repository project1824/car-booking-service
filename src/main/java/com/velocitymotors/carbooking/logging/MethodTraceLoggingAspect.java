package com.velocitymotors.carbooking.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Debug-level entry/exit/timing trace for the service, payment and client classes -
 * pure cross-cutting logging, so aop fits well here (the actual business
 * log.info/log.warn calls stay explicit in the code since they carry meaning this can't
 * infer). Deliberately skips logging arguments/return values - some methods take a
 * booking request or payment reference, and a generic aspect can't know what's safe to
 * print.
 */
@Aspect
@Component
public class MethodTraceLoggingAspect {

    @Around("execution(public * com.velocitymotors.carbooking.service..*(..)) || "
            + "execution(public * com.velocitymotors.carbooking.payment..*(..)) || "
            + "execution(public * com.velocitymotors.carbooking.client..*(..))")
    /** Logs into the target class's own logger, so the trace lines look like they came from that class. */
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
