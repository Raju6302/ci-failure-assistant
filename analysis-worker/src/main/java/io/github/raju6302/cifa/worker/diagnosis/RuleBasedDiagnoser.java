package io.github.raju6302.cifa.worker.diagnosis;

import java.util.List;
import java.util.Map;

/**
 * Turns Phase 2's rule-based analysis into a diagnosis when the agent can't be used
 * (no API key, the model declined, repeated API errors). Always LOW confidence: it
 * reports what the log says, not why.
 */
class RuleBasedDiagnoser {

    private static final Map<String, String> ADVICE = Map.of(
            "COMPILE_ERROR", "Fix the compile error at the location the compiler reports; check recent renames or signature changes.",
            "TEST_FAILURE", "Check the failing assertion against the code under test; compare expected and actual values.",
            "DEPENDENCY", "Check the dependency coordinates and versions in the build file, and that the repository is reachable.",
            "FLAKY_TEST", "Re-run to confirm, then remove the timing or ordering dependence in the test.",
            "INFRASTRUCTURE", "Re-run the job; if it keeps failing, check runner capacity, timeouts and external services the job needs.",
            "CONFIG_ERROR", "Check the workflow and build configuration for the missing file, command or setting named in the log.",
            "UNKNOWN", "Open the failing step's log to find the first error.");

    DiagnosisResult diagnose(FailureContext context, String reason) {
        FailureContext.Analysis analysis = context.analysis();
        String category = analysis == null ? "UNKNOWN" : analysis.category();
        String rootCause = analysis == null
                ? "Job '%s' failed; no log analysis is available.".formatted(context.jobName())
                : analysis.summary();
        List<Diagnosis.Evidence> evidence = analysis == null ? List.of()
                : List.of(new Diagnosis.Evidence("log", "",
                        analysis.excerptStartLine() == null ? 0 : analysis.excerptStartLine(),
                        firstLines(analysis.excerpt(), 8)));
        Diagnosis diagnosis = new Diagnosis(rootCause, category, Diagnosis.Confidence.LOW, evidence,
                ADVICE.getOrDefault(category, ADVICE.get("UNKNOWN")), "");
        return new DiagnosisResult(diagnosis, "RULES", null, 0, List.of(), Usage.NONE, reason);
    }

    private static String firstLines(String text, int count) {
        if (text == null) {
            return "";
        }
        return String.join("\n", text.lines().limit(count).toList());
    }
}
