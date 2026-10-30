package io.github.raju6302.cifa.api.runs;

import io.github.raju6302.cifa.common.RunStatus;
import java.time.Instant;

/** One row of the dashboard's runs table. */
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
        String errorMessage) {
}
