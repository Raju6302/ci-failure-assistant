package io.github.raju6302.cifa.worker.ingest;

import io.github.raju6302.cifa.github.model.CheckRunAnnotation;
import io.github.raju6302.cifa.github.model.JobLog;
import io.github.raju6302.cifa.github.model.WorkflowJob;
import io.github.raju6302.cifa.worker.logs.FailureAnalysis;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class IngestionStore {

    private static final int MAX_ERROR_LENGTH = 1000;

    /** How annotations are kept in {@code failed_job.annotations}. */
    private record StoredAnnotation(String path, Integer startLine, Integer endLine, String level, String title,
                                    String message) {

        static StoredAnnotation of(CheckRunAnnotation annotation) {
            return new StoredAnnotation(annotation.path(), annotation.startLine(), annotation.endLine(),
                    annotation.annotationLevel(), annotation.title(), annotation.message());
        }
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    IngestionStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Marks the run INGESTING and counts the attempt. Runs already finished are skipped;
     * INGESTING is allowed so a run whose worker crashed can be picked up again.
     */
    Optional<RunToIngest> claim(long runId) {
        return jdbc.sql("""
                        UPDATE workflow_run r
                           SET status = 'INGESTING', ingest_attempts = r.ingest_attempts + 1
                          FROM repository repo
                         WHERE r.id = :id
                           AND repo.id = r.repository_id
                           AND r.status IN ('RECEIVED', 'QUEUED', 'INGESTING')
                        RETURNING r.id, repo.full_name, repo.installation_id, r.github_run_id, r.run_attempt,
                                  r.ingest_attempts""")
                .param("id", runId)
                .query((rs, row) -> new RunToIngest(
                        rs.getLong("id"),
                        rs.getString("full_name"),
                        rs.getObject("installation_id", Long.class),
                        rs.getLong("github_run_id"),
                        rs.getInt("run_attempt"),
                        rs.getInt("ingest_attempts")))
                .optional();
    }

    /** @return the stored job's id, also when it was already stored by an earlier attempt */
    long saveFailedJob(long runId, WorkflowJob job, List<String> failedSteps, JobLog log, String redactedLog,
                       List<CheckRunAnnotation> annotations) {
        return jdbc.sql("""
                        INSERT INTO failed_job (workflow_run_id, github_job_id, name, conclusion, failed_steps, html_url,
                                                started_at, completed_at, log_text, log_bytes, log_truncated, annotations)
                        VALUES (:runId, :jobId, :name, :conclusion, :steps, :url, :startedAt, :completedAt,
                                :log, :logBytes, :truncated, CAST(:annotations AS jsonb))
                        ON CONFLICT (github_job_id) DO UPDATE
                           SET conclusion = EXCLUDED.conclusion, failed_steps = EXCLUDED.failed_steps,
                               log_text = EXCLUDED.log_text, log_bytes = EXCLUDED.log_bytes,
                               log_truncated = EXCLUDED.log_truncated, annotations = EXCLUDED.annotations
                        RETURNING id""")
                .param("runId", runId)
                .param("jobId", job.id())
                .param("name", job.name())
                .param("conclusion", job.conclusion())
                .param("steps", new SqlArrayValue("text", failedSteps.toArray()))
                .param("url", job.htmlUrl())
                .param("startedAt", timestamp(job.startedAt()))
                .param("completedAt", timestamp(job.completedAt()))
                .param("log", redactedLog)
                .param("logBytes", log == null ? 0 : log.totalBytes())
                .param("truncated", log != null && log.truncated())
                .param("annotations", json.writeValueAsString(annotations.stream().map(StoredAnnotation::of).toList()))
                .query(Long.class)
                .single();
    }

    /** One analysis per job; re-ingesting the run replaces it. */
    void saveAnalysis(long failedJobId, FailureAnalysis analysis) {
        jdbc.sql("""
                        INSERT INTO failure_analysis (failed_job_id, category, failed_step, summary, error_excerpt,
                                                      error_start_line, error_end_line, code_references, fingerprint, parser)
                        VALUES (:jobId, :category, :step, :summary, :excerpt, :startLine, :endLine,
                                CAST(:references AS jsonb), :fingerprint, :parser)
                        ON CONFLICT (failed_job_id) DO UPDATE
                           SET category = EXCLUDED.category, failed_step = EXCLUDED.failed_step,
                               summary = EXCLUDED.summary, error_excerpt = EXCLUDED.error_excerpt,
                               error_start_line = EXCLUDED.error_start_line, error_end_line = EXCLUDED.error_end_line,
                               code_references = EXCLUDED.code_references, fingerprint = EXCLUDED.fingerprint,
                               parser = EXCLUDED.parser""")
                .param("jobId", failedJobId)
                .param("category", analysis.category().name())
                .param("step", analysis.failedStep())
                .param("summary", analysis.summary())
                .param("excerpt", analysis.excerpt())
                .param("startLine", analysis.excerptStartLine())
                .param("endLine", analysis.excerptEndLine())
                .param("references", json.writeValueAsString(analysis.codeReferences()))
                .param("fingerprint", analysis.fingerprint())
                .param("parser", analysis.parser())
                .update();
    }

    void markIngested(long runId) {
        jdbc.sql("""
                        UPDATE workflow_run SET status = 'INGESTED', ingested_at = now(), error_message = NULL
                         WHERE id = :id""")
                .param("id", runId)
                .update();
    }

    /** Back to RECEIVED: the api-service relay re-queues it after a short delay. */
    void markForRetry(long runId, String error) {
        updateWithError(runId, "RECEIVED", error);
    }

    void markFailed(long runId, String error) {
        updateWithError(runId, "INGEST_FAILED", error);
    }

    private void updateWithError(long runId, String status, String error) {
        String message = error == null ? "Unknown error" : error;
        jdbc.sql("UPDATE workflow_run SET status = :status, error_message = :error WHERE id = :id")
                .param("status", status)
                .param("error", message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message)
                .param("id", runId)
                .update();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
