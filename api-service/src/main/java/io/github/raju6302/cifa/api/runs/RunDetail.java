package io.github.raju6302.cifa.api.runs;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;

/** A run with its failed jobs. Serialized flat: the summary fields plus {@code jobs}. */
public record RunDetail(@JsonUnwrapped RunSummary run, List<FailedJob> jobs) {

    /** @param analysis null until the worker has analyzed the job */
    public record FailedJob(
            long id,
            long githubJobId,
            String name,
            String conclusion,
            List<String> failedSteps,
            long logBytes,
            boolean logTruncated,
            String htmlUrl,
            Instant startedAt,
            Instant completedAt,
            Analysis analysis) {
    }

    /**
     * The worker's rule-based analysis of a failed job.
     *
     * @param category         e.g. {@code COMPILE_ERROR}; see the worker's {@code FailureCategory}
     * @param excerptStartLine 1-based line of the job log where {@code excerpt} starts; null when the
     *                         excerpt was built from check-run annotations because the log expired
     */
    public record Analysis(
            String category,
            String failedStep,
            String summary,
            String excerpt,
            Integer excerptStartLine,
            Integer excerptEndLine,
            List<CodeReference> codeReferences,
            String fingerprint) {
    }

    /** A repo-relative file, and line when known, that the failure points at. */
    public record CodeReference(String path, Integer line) {
    }
}
