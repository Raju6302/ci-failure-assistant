package io.github.raju6302.cifa.worker.ingest;

/** What the worker needs to fetch a run's jobs from GitHub. */
record RunToIngest(long id, String repository, Long installationId, long githubRunId, int runAttempt, int attempt) {
}
