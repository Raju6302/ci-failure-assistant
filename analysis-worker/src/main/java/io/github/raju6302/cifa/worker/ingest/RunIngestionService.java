package io.github.raju6302.cifa.worker.ingest;

import io.github.raju6302.cifa.github.GitHubApiException;
import io.github.raju6302.cifa.github.GitHubClient;
import io.github.raju6302.cifa.github.auth.MissingCredentialsException;
import io.github.raju6302.cifa.github.model.CheckRunAnnotation;
import io.github.raju6302.cifa.github.model.JobLog;
import io.github.raju6302.cifa.github.model.WorkflowJob;
import io.github.raju6302.cifa.worker.logs.FailureAnalysis;
import io.github.raju6302.cifa.worker.logs.FailureAnalyzer;
import io.github.raju6302.cifa.worker.logs.JobEvidence;
import io.github.raju6302.cifa.worker.logs.JobTimeouts;
import io.github.raju6302.cifa.worker.redaction.SecretRedactor;
import io.github.raju6302.cifa.worker.redaction.SecretRedactor.Redaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fetches a failed run's failed jobs, their logs and annotations from GitHub, stores them
 * redacted, and runs the rule-based analysis on each job.
 */
@Service
public class RunIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RunIngestionService.class);
    private static final Set<String> FAILED_JOB_CONCLUSIONS = Set.of("failure", "timed_out");
    /** GitHub's conclusion for a job that hit timeout-minutes, but also for a manual or fail-fast cancel. */
    private static final String CANCELLED = "cancelled";

    private final IngestionStore store;
    private final GitHubClient github;
    private final SecretRedactor redactor;
    private final FailureAnalyzer analyzer;
    private final IngestionProperties properties;

    RunIngestionService(IngestionStore store, GitHubClient github, SecretRedactor redactor, FailureAnalyzer analyzer,
                        IngestionProperties properties) {
        this.store = store;
        this.github = github;
        this.redactor = redactor;
        this.analyzer = analyzer;
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
            List<WorkflowJob> candidates = github
                    .listJobs(run.repository(), run.githubRunId(), run.runAttempt(), run.installationId())
                    .stream()
                    .filter(job -> FAILED_JOB_CONCLUSIONS.contains(job.conclusion()) || CANCELLED.equals(job.conclusion()))
                    .toList();
            int stored = 0;
            for (WorkflowJob job : candidates) {
                List<CheckRunAnnotation> annotations = fetchAnnotations(run, job);
                boolean timedOut = JobTimeouts.exceededTimeLimit(annotations);
                if (CANCELLED.equals(job.conclusion()) && !timedOut) {
                    log.debug("Skipping job {} of run {}: cancelled, not by its time limit", job.id(), run.id());
                    continue;
                }
                ingestJob(run, job, annotations, timedOut);
                stored++;
            }
            store.markIngested(run.id());
            log.info("Ingested run {} ({} #{}): {} failed jobs", run.id(), run.repository(), run.githubRunId(), stored);
            return IngestOutcome.DONE;
        } catch (RuntimeException e) {
            return handleFailure(run, e);
        }
    }

    private void ingestJob(RunToIngest run, WorkflowJob job, List<CheckRunAnnotation> annotations, boolean timedOut) {
        JobLog jobLog = downloadLog(run, job);
        RedactedJob redacted = redact(jobLog, annotations);
        if (redacted.secretsRemoved() > 0) {
            log.info("Redacted {} secret(s) from job {} of run {}", redacted.secretsRemoved(), job.id(), run.id());
        }
        List<String> failedSteps = failedSteps(job, timedOut);
        long failedJobId = store.saveFailedJob(run.id(), job, failedSteps, jobLog, redacted.log(), redacted.annotations());
        analyze(job, new JobEvidence(job.conclusion(), failedSteps, redacted.log(), redacted.annotations()))
                .ifPresent(analysis -> store.saveAnalysis(failedJobId, analysis));
    }

    private record RedactedJob(String log, List<CheckRunAnnotation> annotations, int secretsRemoved) {
    }

    /** Annotations can quote log lines, so they are redacted like the log. */
    private RedactedJob redact(JobLog jobLog, List<CheckRunAnnotation> annotations) {
        Redaction text = redactor.redact(jobLog == null ? null : jobLog.text());
        int removed = text.secretsRemoved();
        List<CheckRunAnnotation> redactedAnnotations = new ArrayList<>(annotations.size());
        for (CheckRunAnnotation annotation : annotations) {
            Redaction title = redactor.redact(annotation.title());
            Redaction message = redactor.redact(annotation.message());
            removed += title.secretsRemoved() + message.secretsRemoved();
            redactedAnnotations.add(new CheckRunAnnotation(annotation.path(), annotation.startLine(), annotation.endLine(),
                    annotation.annotationLevel(), title.text(), message.text()));
        }
        return new RedactedJob(text.text(), redactedAnnotations, removed);
    }

    /**
     * A bug in a parser must not block ingestion: the job is stored either way and simply has
     * no analysis (the dashboard shows it as not analyzed).
     */
    private Optional<FailureAnalysis> analyze(WorkflowJob job, JobEvidence evidence) {
        try {
            return Optional.of(analyzer.analyze(evidence));
        } catch (RuntimeException e) {
            log.error("Could not analyze job {}", job.id(), e);
            return Optional.empty();
        }
    }

    /** A timed-out job's step is "cancelled", which {@link WorkflowJob#failedStepNames()} doesn't count. */
    private static List<String> failedSteps(WorkflowJob job, boolean timedOut) {
        List<String> failed = job.failedStepNames();
        if (!failed.isEmpty() || !timedOut || job.steps() == null) {
            return failed;
        }
        return job.steps().stream().filter(step -> CANCELLED.equals(step.conclusion())).map(WorkflowJob.Step::name).toList();
    }

    /**
     * Annotations add detail but aren't essential: without the Checks permission (403) or for
     * a job without a check run (404) the job is still ingested. Temporary errors retry the run.
     */
    private List<CheckRunAnnotation> fetchAnnotations(RunToIngest run, WorkflowJob job) {
        try {
            return github.listAnnotations(run.repository(), job.id(), run.installationId());
        } catch (GitHubApiException e) {
            if (e.isRetryable()) {
                throw e;
            }
            log.warn("Annotations for job {} of run {} are not available ({}); continuing without them",
                    job.id(), run.id(), e.status());
            return List.of();
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
