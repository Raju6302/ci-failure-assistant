package io.github.raju6302.cifa.worker.diagnosis;

import java.util.List;

/**
 * A diagnosis plus how it was made.
 *
 * @param source     AGENT or RULES
 * @param agentError why the agent wasn't used or didn't finish (RULES only)
 */
public record DiagnosisResult(Diagnosis diagnosis, String source, String model, int steps,
                              List<ToolCall> toolCalls, Usage usage, String agentError) {

    /** One tool call the agent made; arguments are kept short for debugging and evals. */
    public record ToolCall(String name, String arguments, boolean isError, int resultChars) {
    }
}
