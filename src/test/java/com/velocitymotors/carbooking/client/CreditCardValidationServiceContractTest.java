package com.velocitymotors.carbooking.client;

import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openapi4j.core.exception.ResolutionException;
import org.openapi4j.parser.OpenApi3Parser;
import org.openapi4j.parser.model.v3.OpenApi3;
import org.openapi4j.parser.model.v3.Schema;
import org.openapi4j.schema.validator.ValidationData;
import org.openapi4j.schema.validator.v3.SchemaValidator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitymotors.carbooking.client.dto.PaymentStatusRequest;
import com.velocitymotors.carbooking.client.dto.PaymentStatusResponse;

class CreditCardValidationServiceContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static OpenApi3 api;

    @BeforeAll
    static void parseSpec() throws Exception {
        URL specUrl = CreditCardValidationServiceContractTest.class.getClassLoader()
                .getResource("openapi/credit-card-validation-service.yaml");
        api = new OpenApi3Parser().parse(specUrl, true);
    }

    @Test
    void specParsesAsStructurallyValidOpenApi() {
        assertThat(api).isNotNull();
        assertThat(api.getPaths()).containsKey("/payment-status");
    }

    @Test
    void handWrittenRequestDtoMatchesTheRequiredPaymentReferenceField() throws Exception {
        Schema requestSchema = api.getComponents().getSchema("PaymentStatusRetrievalRequest");
        JsonNode schemaNode = requestSchema.toNode();

        JsonNode validRequest = MAPPER.valueToTree(new PaymentStatusRequest("DL123456789"));
        assertThat(validate(schemaNode, validRequest).isValid()).isTrue();

        // paymentReference is `required` in the spec - openapi4j does enforce that keyword
        // correctly (unlike the enum below), so a request missing it must fail validation.
        JsonNode missingReference = MAPPER.createObjectNode();
        assertThat(validate(schemaNode, missingReference).isValid()).isFalse();
    }

    @Test
    void handWrittenResponseDtoMatchesTheSpecShape() throws Exception {
        Schema responseSchema = api.getComponents().getSchema("PaymentStatusResponse");
        JsonNode schemaNode = responseSchema.toNode();

        JsonNode approved = MAPPER.valueToTree(new PaymentStatusResponse("2026-09-06T10:00:00Z", "APPROVED"));
        assertThat(validate(schemaNode, approved).isValid()).isTrue();

        JsonNode rejected = MAPPER.valueToTree(new PaymentStatusResponse("2026-09-06T10:00:00Z", "REJECTED"));
        assertThat(validate(schemaNode, rejected).isValid()).isTrue();
    }

    @Test
    void statusEnumIsActuallyEnforced() throws Exception {
        Schema responseSchema = api.getComponents().getSchema("PaymentStatusResponse");
        JsonNode schemaNode = responseSchema.toNode();

        // a status outside APPROVED/REJECTED must now fail validation. Before the fix, this
        // exact input validated as `true`, which was the bug.
        JsonNode bogusStatus = MAPPER.valueToTree(new PaymentStatusResponse("2026-09-06T10:00:00Z", "NOT_A_REAL_STATUS"));
        assertThat(validate(schemaNode, bogusStatus).isValid()).isFalse();
    }

    private static ValidationData<Void> validate(JsonNode schemaNode, JsonNode instance) throws ResolutionException {
        SchemaValidator validator = new SchemaValidator("contractCheck", schemaNode);
        ValidationData<Void> result = new ValidationData<>();
        validator.validate(instance, result);
        return result;
    }
}
