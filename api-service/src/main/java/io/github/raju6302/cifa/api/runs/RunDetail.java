package io.github.raju6302.cifa.api.runs;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;

/** A run with its failed jobs. Serialized flat: the summary fields plus {@code jobs}. */
public record RunDetail(@JsonUnwrapped RunSummary run, List<FailedJob> jobs) {

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
            Instant completedAt) {
    }
}
