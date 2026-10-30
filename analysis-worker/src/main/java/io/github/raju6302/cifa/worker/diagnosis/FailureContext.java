package io.github.raju6302.cifa.worker.diagnosis;

import java.util.List;

/** Everything the agent is told up front about one failed job. */
public record FailureContext(
        long workflowRunId,
        long failedJobId,
        String repository,
        String workflowName,
        long githubRunId,
        String headBranch,
        String headSha,
        String event,
        long githubJobId,
        String jobName,
        String conclusion,
        List<String> failedSteps,
        Analysis analysis,
        List<CodeSnippet> relatedCode,
        List<PastFailure> similarFailures) {

    /** Phase 2's rule-based analysis of the log. */
    public record Analysis(
            String category,
            String failedStep,
            String summary,
            String excerpt,
            Integer excerptStartLine,
            List<CodeReference> codeReferences,
            String fingerprint) {
    }

    public record CodeReference(String path, Integer line) {
    }

    /** A code chunk retrieved by RAG (Phase 5). */
    public record CodeSnippet(String path, int startLine, int endLine, String symbol, String content) {
    }

    /** A previous failure that looks like this one (Phase 5 failure memory). */
    public record PastFailure(long failedJobId, String headSha, String createdAt, boolean sameFingerprint,
                              String rootCause, String resolution) {
    }
}
