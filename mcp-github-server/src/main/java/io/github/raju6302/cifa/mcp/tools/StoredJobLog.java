package io.github.raju6302.cifa.mcp.tools;

import java.util.List;

/**
 * A failed job as the worker stored it.
 *
 * @param logText   the redacted log, or null when GitHub had already deleted it
 * @param truncated true when only the end of the log was kept
 */
record StoredJobLog(
        long githubJobId,
        String name,
        String conclusion,
        List<String> failedSteps,
        String repository,
        long githubRunId,
        String logText,
        boolean truncated) {
}
