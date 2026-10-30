package io.github.raju6302.cifa.worker.logs;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides the final category. Parsers know their tool's errors; this adds what applies to any
 * tool: the runner itself failing, the network failing underneath a tool, and explicit flaky
 * markers.
 */
final class FailureClassifier {

    /** The machine or the runner failed; nothing the code did. Wins over any parser result. */
    private static final Pattern RUNNER_PROBLEM = Pattern.compile(
            "The runner has received a shutdown signal|lost communication with the server|No space left on device"
                    + "|exit code 137\\b|\\bOOMKilled\\b|java\\.lang\\.OutOfMemoryError|JavaScript heap out of memory"
                    + "|Cannot allocate memory|The action has timed out", Pattern.CASE_INSENSITIVE);
    /** The network or a registry failed. Only trusted when the parser saw a download problem or nothing specific. */
    private static final Pattern NETWORK_PROBLEM = Pattern.compile(
            "\\b(?:ETIMEDOUT|ECONNRESET|EAI_AGAIN)\\b|Could not resolve host|Temporary failure in name resolution"
                    + "|Connection (?:timed out|reset)|TLS handshake timeout|i/o timeout|\\btoomanyrequests\\b|Read timed out"
                    + "|\\b(?:502 Bad Gateway|503 Service Unavailable|504 Gateway Time-?out)\\b");
    private static final Set<FailureCategory> DOWNLOAD_OR_UNKNOWN = Set.of(FailureCategory.DEPENDENCY, FailureCategory.UNKNOWN);
    /**
     * Test tools that retry failed tests say so: Surefire's {@code Flakes: 1} with
     * {@code rerunFailingTestsCount}, pytest-rerunfailures' {@code RERUN} and "1 rerun", and
     * Jest's {@code retryTimes} logging. A single log can't prove flakiness otherwise; the
     * history-based check (same commit fails, then passes) comes in Phase 7. The plain word
     * "flaky" is deliberately not used: it shows up in paths and job names.
     */
    private static final Pattern FLAKY_MARKER = Pattern.compile(
            "\\bFlakes: [1-9]\\d{0,5}|^\\[WARNING\\] Flakes: ?$|\\b[1-9]\\d{0,5} rerun\\b|^\\S{1,300}\\.py::\\S{1,300} RERUN\\b"
                    + "|^RERUN \\S|LOGGING RETRY ERRORS");

    private FailureClassifier() {
    }

    static FailureCategory classify(FailureCategory parsed, StepLog step, List<String> annotationMessages, boolean timedOut) {
        if (timedOut || anyMatch(RUNNER_PROBLEM, step, annotationMessages)) {
            return FailureCategory.INFRASTRUCTURE;
        }
        if (DOWNLOAD_OR_UNKNOWN.contains(parsed) && anyMatch(NETWORK_PROBLEM, step, annotationMessages)) {
            return FailureCategory.INFRASTRUCTURE;
        }
        if (parsed == FailureCategory.TEST_FAILURE && step != null && step.contains(FLAKY_MARKER)) {
            return FailureCategory.FLAKY_TEST;
        }
        return parsed;
    }

    private static boolean anyMatch(Pattern pattern, StepLog step, List<String> messages) {
        return step != null && step.contains(pattern) || messages.stream().anyMatch(message -> pattern.matcher(message).find());
    }
}
