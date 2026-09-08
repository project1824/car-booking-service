package com.velocitymotors.carbooking.client.openapi;

import java.net.URL;

import org.openapi4j.parser.OpenApi3Parser;
import org.openapi4j.parser.model.v3.MediaType;
import org.openapi4j.parser.model.v3.OpenApi3;
import org.openapi4j.parser.model.v3.Operation;
import org.openapi4j.parser.model.v3.Response;
import org.openapi4j.schema.validator.ValidationContext;
import org.openapi4j.schema.validator.ValidationData;
import org.openapi4j.schema.validator.v3.SchemaValidator;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Checks real credit-card-validation-service request/response traffic against the bundled
 * OpenAPI contract ({@code openapi/credit-card-validation-service.yaml}) - live, not just in
 * a build-time test.
 *
 * <p>Deliberately observability-only: a mismatch is logged and counted
 * ({@code credit_card_contract_check_total}), never thrown. Whether a booking is
 * confirmed or declined is still decided entirely by
 * {@link com.velocitymotors.carbooking.payment.CreditCardPaymentStrategy}'s own status check.
 * The reasoning: the upstream drifting from its documented contract is worth knowing about,
 * but it must never be able to hard-fail a real customer's booking over a schema technicality
 * that {@code CreditCardPaymentStrategy} would have handled fine on its own (it already
 * treats anything other than a literal {@code "APPROVED"} as declined).
 *
 * <p>Same reasoning applies to spec loading itself: if the bundled YAML is ever missing or
 * broken, this component logs it and disables itself rather than stopping the application
 * from starting - a typo in a contract-checking aid should never become a production outage.
 */
@Slf4j
@Component
public class CreditCardValidationContractValidator {

    static final String OPENAPI_RESOURCE = "openapi/credit-card-validation-service.yaml";
    static final String PAYMENT_STATUS_PATH = "/payment-status";
    static final String CONTRACT_CHECK_METRIC = "credit_card_contract_check_total";

    private final MeterRegistry meterRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    private OpenApi3 openApi;
    private Operation paymentStatusOperation;
    private SchemaValidator requestBodyValidator;
    private boolean enabled;

    public CreditCardValidationContractValidator(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        try {
            URL specUrl = getClass().getClassLoader().getResource(OPENAPI_RESOURCE);
            if (specUrl == null) {
                throw new IllegalStateException("Missing OpenAPI spec on classpath: " + OPENAPI_RESOURCE);
            }
            this.openApi = new OpenApi3Parser().parse(specUrl, true);
            this.paymentStatusOperation = openApi.getPath(PAYMENT_STATUS_PATH).getPost();
            if (paymentStatusOperation == null) {
                throw new IllegalStateException("OpenAPI spec is missing POST " + PAYMENT_STATUS_PATH);
            }
            MediaType requestMediaType = paymentStatusOperation.getRequestBody().getContentMediaType("application/json");
            this.requestBodyValidator = new SchemaValidator(
                    new ValidationContext<>(openApi.getContext()), null, requestMediaType.getSchema().toNode());
            this.enabled = true;
        } catch (Exception ex) {
            log.error("Disabling credit-card-validation-service contract checking - failed to load {}: {}",
                    OPENAPI_RESOURCE, ex.getMessage(), ex);
            this.enabled = false;
        }
    }

    public void validateRequest(String requestJson) {
        if (!enabled) {
            return;
        }
        try {
            JsonNode body = mapper.readTree(requestJson);
            ValidationData<Void> validation = new ValidationData<>();
            requestBodyValidator.validate(body, validation);
            recordOutcome("request", validation);
        } catch (Exception ex) {
            recordFailure("request", ex);
        }
    }

    public void validateResponse(int statusCode, String responseJson) {
        if (!enabled) {
            return;
        }
        try {
            Response responseSpec = paymentStatusOperation.getResponses().get(String.valueOf(statusCode));
            if (responseSpec == null) {
                recordMismatch("response", "HTTP status " + statusCode + " is not defined in the OpenAPI spec");
                return;
            }
            MediaType responseMediaType = responseSpec.getContentMediaType("application/json");
            if (responseMediaType == null) {
                if (responseJson == null || responseJson.isBlank()) {
                    recordValid("response");
                } else {
                    recordMismatch("response", "HTTP status " + statusCode
                            + " does not define an application/json body in the OpenAPI spec");
                }
                return;
            }
            SchemaValidator responseBodyValidator = new SchemaValidator(
                    new ValidationContext<>(openApi.getContext()), null, responseMediaType.getSchema().toNode());
            JsonNode body = mapper.readTree(responseJson);
            ValidationData<Void> validation = new ValidationData<>();
            responseBodyValidator.validate(body, validation);
            recordOutcome("response", validation);
        } catch (Exception ex) {
            recordFailure("response", ex);
        }
    }

    private void recordOutcome(String direction, ValidationData<Void> validation) {
        if (validation.isValid()) {
            recordValid(direction);
        } else {
            recordMismatch(direction, validation.results().toString());
        }
    }

    private void recordValid(String direction) {
        meterRegistry.counter(CONTRACT_CHECK_METRIC, "direction", direction, "outcome", "valid").increment();
    }

    private void recordMismatch(String direction, String details) {
        meterRegistry.counter(CONTRACT_CHECK_METRIC, "direction", direction, "outcome", "mismatch").increment();
        log.warn("credit-card-validation-service {} does not match its OpenAPI contract: {}", direction, details);
    }

    private void recordFailure(String direction, Exception ex) {
        meterRegistry.counter(CONTRACT_CHECK_METRIC, "direction", direction, "outcome", "check_failed").increment();
        log.warn("Could not run the OpenAPI contract check on the {} payload: {}", direction, ex.getMessage());
    }
}
