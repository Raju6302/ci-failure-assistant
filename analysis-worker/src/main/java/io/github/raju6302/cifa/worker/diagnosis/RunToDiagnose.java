package io.github.raju6302.cifa.worker.diagnosis;

/** A claimed run, with what's needed to fetch code and build each job's context. */
public record RunToDiagnose(long id, long repositoryId, String repository, Long installationId, long githubRunId,
                            String workflowName, String event, String headBranch, String headSha, int attempt) {
}
