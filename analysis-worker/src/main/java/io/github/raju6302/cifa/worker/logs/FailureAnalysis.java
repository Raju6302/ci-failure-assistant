package io.github.raju6302.cifa.worker.logs;

import java.util.List;

/**
 * What the rule-based analysis found in one failed job.
 *
 * @param failedStep       the step that failed, as GitHub names it, or null if unknown
 * @param summary          one line naming the actual error
 * @param excerpt          the relevant log lines with a little context, without timestamps or ANSI codes
 * @param excerptStartLine 1-based line in the stored log where the excerpt starts; null when the
 *                         excerpt comes from annotations because there is no log
 * @param fingerprint      equal for the same failure on another commit, so repeats can be grouped
 * @param parser           which parser produced the finding, for debugging and evals
 */
public record FailureAnalysis(
        FailureCategory category,
        String failedStep,
        String summary,
        String excerpt,
        Integer excerptStartLine,
        Integer excerptEndLine,
        List<CodeReference> codeReferences,
        String fingerprint,
        String parser) {

    public FailureAnalysis {
        codeReferences = List.copyOf(codeReferences);
    }
}
