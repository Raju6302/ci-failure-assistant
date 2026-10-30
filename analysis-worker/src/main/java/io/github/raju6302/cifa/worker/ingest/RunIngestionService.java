package io.github.raju6302.cifa.worker.ingest;

import io.github.raju6302.cifa.github.GitHubApiException;
import io.github.raju6302.cifa.github.GitHubClient;
import io.github.raju6302.cifa.github.auth.MissingCredentialsException;
import io.github.raju6302.cifa.github.model.JobLog;
import io.github.raju6302.cifa.github.model.WorkflowJob;
import io.github.raju6302.cifa.worker.redaction.SecretRedactor;
import io.github.raju6302.cifa.worker.redaction.SecretRedactor.Redaction;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Fetches a failed run's failed jobs and their logs from GitHub and stores them. */
@Service
public class RunIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RunIngestionService.class);
    private static final Set<String> FAILED_JOB_CONCLUSIONS = Set.of("failure", "timed_out");

    private final IngestionStore store;
    private final GitHubClient github;
    private final SecretRedactor redactor;
    private final IngestionProperties properties;

    RunIngestionService(IngestionStore store, GitHubClient github, SecretRedactor redactor, IngestionProperties properties) {
        this.store = store;
        this.github = github;
        this.redactor = redactor;
        this.properties = properties;
    }

    public IngestOutcome ingest(long runId) {
        Optional<RunToIngest> claimed = store.claim(runId);
        if (claimed.isEmpty()) {
            log.debug("Run {} is already ingested or unknown; skipping", runId);
            return IngestOutcome.DONE;
        }
        RunToIngest run = claimed.get();
        try {
            List<WorkflowJob> failedJobs = github
                    .listJobs(run.repository(), run.githubRunId(), run.runAttempt(), run.installationId())
                    .stream()
                    .filter(job -> FAILED_JOB_CONCLUSIONS.contains(job.conclusion()))
                    .toList();
            for (WorkflowJob job : failedJobs) {
                JobLog jobLog = downloadLog(run, job);
                Redaction redacted = redactor.redact(jobLog == null ? null : jobLog.text());
                if (redacted.secretsRemoved() > 0) {
                    log.info("Redacted {} secret(s) from job {} of run {}", redacted.secretsRemoved(), job.id(), run.id());
                }
                store.saveFailedJob(run.id(), job, jobLog, redacted.text());
            }
            store.markIngested(run.id());
            log.info("Ingested run {} ({} #{}): {} failed jobs",
                    run.id(), run.repository(), run.githubRunId(), failedJobs.size());
            return IngestOutcome.DONE;
        } catch (RuntimeException e) {
            return handleFailure(run, e);
        }
    }

    /** Logs expire (GitHub keeps them 90 days by default); a missing log is not a failure. */
    private JobLog downloadLog(RunToIngest run, WorkflowJob job) {
        try {
            return github.downloadJobLog(run.repository(), job.id(), run.installationId());
        } catch (GitHubApiException e) {
            if (e.isNotFound()) {
                log.warn("Log for job {} of run {} is no longer available", job.id(), run.id());
                return null;
            }
            throw e;
        }
    }

    private IngestOutcome handleFailure(RunToIngest run, RuntimeException e) {
        boolean permanent = isPermanent(e);
        if (permanent || run.attempt() >= properties.maxAttempts()) {
            log.error("Giving up on run {} after {} attempt(s)", run.id(), run.attempt(), e);
            store.markFailed(run.id(), e.getMessage());
            return IngestOutcome.DONE;
        }
        log.warn("Attempt {} for run {} failed; will retry: {}", run.attempt(), run.id(), e.getMessage());
        store.markForRetry(run.id(), "Attempt %d failed: %s".formatted(run.attempt(), e.getMessage()));
        return IngestOutcome.RETRY;
    }

    /** Errors that will not fix themselves: bad config, missing permissions, deleted repos. */
    private static boolean isPermanent(RuntimeException e) {
        return e instanceof MissingCredentialsException
                || e instanceof IllegalArgumentException
                || e instanceof GitHubApiException api && !api.isRetryable();
    }
}
