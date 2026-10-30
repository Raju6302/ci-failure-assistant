package io.github.raju6302.cifa.github.model;

/**
 * A workflow run, as returned by the Actions API.
 *
 * @param path the workflow file, e.g. {@code .github/workflows/ci.yml}
 */
public record WorkflowRun(
        long id,
        String name,
        String path,
        String headSha,
        String headBranch,
        int runAttempt,
        String htmlUrl) {
}
