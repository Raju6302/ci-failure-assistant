package io.github.raju6302.cifa.worker.ingest;

import io.github.raju6302.cifa.github.model.JobLog;
import io.github.raju6302.cifa.github.model.WorkflowJob;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
class IngestionStore {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcClient jdbc;

    IngestionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
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

    void saveFailedJob(long runId, WorkflowJob job, JobLog log, String redactedLog) {
        jdbc.sql("""
                        INSERT INTO failed_job (workflow_run_id, github_job_id, name, conclusion, failed_steps, html_url,
                                                started_at, completed_at, log_text, log_bytes, log_truncated)
                        VALUES (:runId, :jobId, :name, :conclusion, :steps, :url, :startedAt, :completedAt,
                                :log, :logBytes, :truncated)
                        ON CONFLICT (github_job_id) DO UPDATE
                           SET conclusion = EXCLUDED.conclusion, failed_steps = EXCLUDED.failed_steps,
                               log_text = EXCLUDED.log_text, log_bytes = EXCLUDED.log_bytes,
                               log_truncated = EXCLUDED.log_truncated""")
                .param("runId", runId)
                .param("jobId", job.id())
                .param("name", job.name())
                .param("conclusion", job.conclusion())
                .param("steps", new SqlArrayValue("text", job.failedStepNames().toArray()))
                .param("url", job.htmlUrl())
                .param("startedAt", timestamp(job.startedAt()))
                .param("completedAt", timestamp(job.completedAt()))
                .param("log", redactedLog)
                .param("logBytes", log == null ? 0 : log.totalBytes())
                .param("truncated", log != null && log.truncated())
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
