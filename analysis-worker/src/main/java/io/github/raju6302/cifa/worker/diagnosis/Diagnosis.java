package io.github.raju6302.cifa.worker.diagnosis;

import java.util.List;

/**
 * The structured answer for one failed job: what broke, why, how sure we are, the
 * evidence, and a fix. Produced by the agent's {@code submit_diagnosis} tool call, or by
 * {@link RuleBasedDiagnoser} when the agent can't be used.
 *
 * @param patch unified diff, or empty when no safe patch can be suggested
 */
public record Diagnosis(
        String rootCause,
        String category,
        Confidence confidence,
        List<Evidence> evidence,
        String suggestedFix,
        String patch) {

    public enum Confidence { HIGH, MEDIUM, LOW }

    /**
     * @param source where the evidence comes from: "log", "file", "diff" or "past_failure"
     * @param path   repo-relative file path, or empty for log evidence
     * @param line   1-based line in that file or log, or 0 when not applicable
     * @param quote  the exact text, copied from the tool output or log
     */
    public record Evidence(String source, String path, int line, String quote) {
    }
}
