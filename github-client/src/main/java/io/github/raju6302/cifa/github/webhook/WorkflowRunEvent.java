package io.github.raju6302.cifa.github.webhook;

import java.util.List;
import java.util.Set;

/** The parts of a {@code workflow_run} webhook payload this project uses. */
public record WorkflowRunEvent(String action, WorkflowRun workflowRun, Repository repository, Installation installation) {

    /** Conclusions that mean "the build broke" and are worth diagnosing. */
    public static final Set<String> FAILED_CONCLUSIONS = Set.of("failure", "timed_out", "startup_failure");

    public boolean isCompletedFailure() {
        return "completed".equals(action)
                && workflowRun != null
                && FAILED_CONCLUSIONS.contains(workflowRun.conclusion());
    }

    public Long installationId() {
        return installation == null ? null : installation.id();
    }

    public record WorkflowRun(
            long id,
            String name,
            int runNumber,
            int runAttempt,
            String event,
            String conclusion,
            String headBranch,
            String headSha,
            String htmlUrl,
            List<PullRequest> pullRequests) {

        public int[] pullRequestNumbers() {
            return pullRequests == null ? new int[0]
                    : pullRequests.stream().mapToInt(PullRequest::number).toArray();
        }
    }

    public record PullRequest(int number) {
    }

    public record Repository(long id, String fullName) {
    }

    public record Installation(long id) {
    }
}
