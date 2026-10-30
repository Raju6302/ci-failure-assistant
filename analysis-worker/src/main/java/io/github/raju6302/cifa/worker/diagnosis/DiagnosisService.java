package io.github.raju6302.cifa.worker.diagnosis;

import io.github.raju6302.cifa.worker.rag.RetrievalService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Diagnoses every failed job of an ingested run: builds each job's context (log analysis,
 * retrieved code, similar past failures), runs the agent, and falls back to the rule-based
 * diagnosis when the agent can't be used. Jobs that already have a diagnosis are skipped,
 * so a retried run only redoes what's missing.
 */
@Service
public class DiagnosisService {

    /** What to do with the queue entry. */
    public enum Outcome {
        /** Done (diagnosed, failed for good, or nothing to do): acknowledge. */
        DONE,
        /** Temporary error: leave unacknowledged so it's reclaimed and retried. */
        RETRY
    }

    private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);

    private final DiagnosisStore store;
    private final Optional<DiagnosisAgent> agent;
    private final RuleBasedDiagnoser rules = new RuleBasedDiagnoser();
    private final RetrievalService retrieval;
    private final DiagnosisProperties properties;
    private final Duration staleAfter;

    DiagnosisService(DiagnosisStore store, Optional<DiagnosisAgent> agent, RetrievalService retrieval,
                     DiagnosisProperties properties,
                     io.github.raju6302.cifa.worker.ingest.IngestionProperties ingestion) {
        this.store = store;
        this.agent = agent;
        this.retrieval = retrieval;
        this.properties = properties;
        // A DIAGNOSING run is only taken over once its queue entry would be reclaimed anyway.
        this.staleAfter = ingestion.reclaimIdle();
    }

    public Outcome diagnose(long runId) {
        Optional<RunToDiagnose> claimed = store.claim(runId, staleAfter);
        if (claimed.isEmpty()) {
            log.debug("Run {} is not ready for diagnosis or is being diagnosed; skipping", runId);
            return Outcome.DONE;
        }
        RunToDiagnose run = claimed.get();
        try {
            List<JobToDiagnose> jobs = store.jobsWithoutDiagnosis(run.id());
            if (!jobs.isEmpty()) {
                retrieval.prepare(run);
            }
            for (JobToDiagnose job : jobs) {
                FailureContext context = contextFor(run, job);
                DiagnosisResult result;
                try {
                    result = agent.map(a -> a.diagnose(context))
                            .orElseGet(() -> rules.diagnose(context, "The diagnosis agent is disabled"));
                } catch (AgentException e) {
                    if (e.isRetryable() && run.attempt() < properties.maxAttempts()) {
                        log.warn("Diagnosis attempt {} for run {} hit a temporary error; will retry: {}",
                                run.attempt(), run.id(), e.getMessage());
                        store.recordRetry(run.id(), "Attempt %d: %s".formatted(run.attempt(), e.getMessage()));
                        return Outcome.RETRY;
                    }
                    log.warn("Agent couldn't diagnose job {}; using the rule-based diagnosis: {}",
                            job.failedJobId(), e.getMessage());
                    result = rules.diagnose(context, e.getMessage());
                }
                store.save(job.failedJobId(), result);
                retrieval.remember(run, job, result.diagnosis().category(), result.diagnosis().rootCause());
            }
            store.markDiagnosed(run.id());
            return Outcome.DONE;
        } catch (RuntimeException e) {
            log.error("Diagnosis of run {} failed", run.id(), e);
            if (run.attempt() < properties.maxAttempts()) {
                store.recordRetry(run.id(), e.getMessage());
                return Outcome.RETRY;
            }
            store.markFailed(run.id(), e.getMessage());
            return Outcome.DONE;
        }
    }

    /** True when ingestion finished and the run should go to the diagnosis queue. */
    public boolean isReadyForDiagnosis(long runId) {
        return store.isIngested(runId);
    }

    /** Ingested runs older than {@code olderThan} that are still waiting: their queue entry was lost. */
    public List<Long> runsMissingFromQueue(Duration olderThan) {
        return store.findStuckInIngested(olderThan, 100);
    }

    private FailureContext contextFor(RunToDiagnose run, JobToDiagnose job) {
        return new FailureContext(run.id(), job.failedJobId(), run.repository(), run.workflowName(), run.githubRunId(),
                run.headBranch(), run.headSha(), run.event(), job.githubJobId(), job.name(), job.conclusion(),
                job.failedSteps(), job.analysis(), retrieval.relatedCode(run, job), retrieval.similarFailures(run, job));
    }
}
