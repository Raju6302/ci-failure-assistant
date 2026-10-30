package io.github.raju6302.cifa.api.runs;

import io.github.raju6302.cifa.common.RunStatus;
import java.time.Instant;

/**
 * One row of the dashboard's runs table.
 *
 * @param category failure category of the run's first failed job, or null if not analyzed (yet)
 * @param summary  that job's one-line error summary, or null
 */
public record RunSummary(
        long id,
        String repository,
        String workflowName,
        String branch,
        String headSha,
        int runNumber,
        int runAttempt,
        String event,
        String conclusion,
        RunStatus status,
        int failedJobCount,
        String htmlUrl,
        Instant receivedAt,
        Instant ingestedAt,
        String errorMessage,
        String category,
        String summary) {
}
