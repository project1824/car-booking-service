package com.velocitymotors.carbooking.client.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Proves the "log-only, never blocks a real request" contract described in
 */
class CreditCardValidationContractValidatorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CreditCardValidationContractValidator validator =
            new CreditCardValidationContractValidator(meterRegistry);

    @Test
    void validRequestIsCountedAsValid() {
        assertThatCode(() -> validator.validateRequest("{\"paymentReference\":\"DL123456789\"}"))
                .doesNotThrowAnyException();

        assertThat(counterCount("request", "valid")).isEqualTo(1.0);
    }

    @Test
    void requestMissingTheRequiredFieldIsCountedAsMismatchWithoutThrowing() {
        assertThatCode(() -> validator.validateRequest("{}")).doesNotThrowAnyException();

        assertThat(counterCount("request", "mismatch")).isEqualTo(1.0);
    }

    @Test
    void validResponseIsCountedAsValid() {
        assertThatCode(() -> validator.validateResponse(200,
                "{\"lastUpdateDate\":\"2026-01-01T00:00:00Z\",\"status\":\"APPROVED\"}"))
                .doesNotThrowAnyException();

        assertThat(counterCount("response", "valid")).isEqualTo(1.0);
    }

    @Test
    void responseWithAStatusOutsideTheEnumIsCountedAsMismatchWithoutThrowing() {
        assertThatCode(() -> validator.validateResponse(200,
                "{\"lastUpdateDate\":\"2026-01-01T00:00:00Z\",\"status\":\"NOT_A_REAL_STATUS\"}"))
                .doesNotThrowAnyException();

        assertThat(counterCount("response", "mismatch")).isEqualTo(1.0);
    }

    @Test
    void responseWithAnHttpStatusTheSpecDoesNotDefineIsCountedAsMismatchWithoutThrowing() {
        assertThatCode(() -> validator.validateResponse(599, "{}")).doesNotThrowAnyException();

        assertThat(counterCount("response", "mismatch")).isEqualTo(1.0);
    }

    private double counterCount(String direction, String outcome) {
        return meterRegistry.get(CreditCardValidationContractValidator.CONTRACT_CHECK_METRIC)
                .tag("direction", direction)
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
