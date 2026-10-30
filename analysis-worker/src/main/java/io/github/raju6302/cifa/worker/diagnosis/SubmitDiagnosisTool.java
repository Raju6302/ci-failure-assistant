package io.github.raju6302.cifa.worker.diagnosis;

import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaTool;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tool the agent calls to finish. It's {@code strict}, so the API guarantees the
 * arguments match the schema; {@link #parse} still checks the values make sense.
 * (Forcing a tool call isn't available on this model, so the prompt asks for it instead.)
 */
final class SubmitDiagnosisTool {

    static final String NAME = "submit_diagnosis";

    static final Set<String> CATEGORIES = Set.of("COMPILE_ERROR", "TEST_FAILURE", "DEPENDENCY", "FLAKY_TEST",
            "INFRASTRUCTURE", "CONFIG_ERROR", "UNKNOWN");

    private SubmitDiagnosisTool() {
    }

    static BetaTool definition() {
        Map<String, Object> evidence = Map.of(
                "type", "object",
                "properties", Map.of(
                        "source", Map.of("type", "string", "enum", List.of("log", "file", "diff", "past_failure")),
                        "path", Map.of("type", "string", "description", "Repo-relative path; empty for log evidence"),
                        "line", Map.of("type", "integer", "description", "1-based line number, or 0 if unknown"),
                        "quote", Map.of("type", "string", "description", "Exact text copied from the source")),
                "required", List.of("source", "path", "line", "quote"),
                "additionalProperties", false);
        return BetaTool.builder()
                .name(NAME)
                .description("Submit the final diagnosis of the failed job. Call exactly once, when done investigating.")
                .strict(true)
                .inputSchema(BetaTool.InputSchema.builder()
                        .properties(BetaTool.InputSchema.Properties.builder()
                                .putAdditionalProperty("rootCause", JsonValue.from(Map.of("type", "string")))
                                .putAdditionalProperty("category", JsonValue.from(Map.of(
                                        "type", "string", "enum", CATEGORIES.stream().sorted().toList())))
                                .putAdditionalProperty("confidence", JsonValue.from(Map.of(
                                        "type", "string", "enum", List.of("HIGH", "MEDIUM", "LOW"))))
                                .putAdditionalProperty("evidence", JsonValue.from(Map.of("type", "array", "items", evidence)))
                                .putAdditionalProperty("suggestedFix", JsonValue.from(Map.of("type", "string")))
                                .putAdditionalProperty("patch", JsonValue.from(Map.of(
                                        "type", "string", "description", "Unified diff, or empty string")))
                                .build())
                        .required(List.of("rootCause", "category", "confidence", "evidence", "suggestedFix", "patch"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    /** @throws InvalidDiagnosisException if a required value is blank or unknown */
    static Diagnosis parse(JsonValue input) {
        Diagnosis diagnosis;
        try {
            diagnosis = input.convert(Diagnosis.class);
        } catch (RuntimeException e) {
            throw new InvalidDiagnosisException("submit_diagnosis arguments don't match the schema: " + e.getMessage());
        }
        if (diagnosis == null || isBlank(diagnosis.rootCause()) || isBlank(diagnosis.suggestedFix())) {
            throw new InvalidDiagnosisException("rootCause and suggestedFix must not be empty");
        }
        if (!CATEGORIES.contains(diagnosis.category()) || diagnosis.confidence() == null) {
            throw new InvalidDiagnosisException("unknown category or confidence");
        }
        return new Diagnosis(diagnosis.rootCause().strip(), diagnosis.category(), diagnosis.confidence(),
                diagnosis.evidence() == null ? List.of() : diagnosis.evidence(),
                diagnosis.suggestedFix().strip(), diagnosis.patch() == null ? "" : diagnosis.patch());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static class InvalidDiagnosisException extends RuntimeException {
        InvalidDiagnosisException(String message) {
            super(message);
        }
    }
}
