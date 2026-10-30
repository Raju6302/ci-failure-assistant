package io.github.raju6302.cifa.worker.diagnosis;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class DiagnosisStore {

    private static final int MAX_ERROR_LENGTH = 1000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    DiagnosisStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Marks the run DIAGNOSING. A run already DIAGNOSING is only taken over when its attempt
     * started more than {@code staleAfter} ago (its worker probably died), so two workers
     * never pay for the same LLM calls at once.
     */
    Optional<RunToDiagnose> claim(long runId, Duration staleAfter) {
        return jdbc.sql("""
                        UPDATE workflow_run r
                           SET status = 'DIAGNOSING', diagnosis_attempts = r.diagnosis_attempts + 1,
                               diagnosis_started_at = now()
                          FROM repository repo
                         WHERE r.id = :id
                           AND repo.id = r.repository_id
                           AND (r.status = 'INGESTED'
                                OR r.status = 'DIAGNOSING'
                                   AND r.diagnosis_started_at < now() - make_interval(secs => :staleSeconds))
                        RETURNING r.id, repo.id AS repository_id, repo.full_name, repo.installation_id, r.github_run_id,
                                  r.workflow_name, r.event, r.head_branch, r.head_sha, r.diagnosis_attempts""")
                .param("id", runId)
                .param("staleSeconds", staleAfter.toSeconds())
                .query((rs, row) -> new RunToDiagnose(
                        rs.getLong("id"),
                        rs.getLong("repository_id"),
                        rs.getString("full_name"),
                        rs.getObject("installation_id", Long.class),
                        rs.getLong("github_run_id"),
                        rs.getString("workflow_name"),
                        rs.getString("event"),
                        rs.getString("head_branch"),
                        rs.getString("head_sha"),
                        rs.getInt("diagnosis_attempts")))
                .optional();
    }

    List<JobToDiagnose> jobsWithoutDiagnosis(long runId) {
        return jdbc.sql("""
                        SELECT j.id, j.github_job_id, j.name, j.conclusion, j.failed_steps,
                               a.category, a.failed_step, a.summary, a.error_excerpt, a.error_start_line,
                               a.code_references::text AS code_references, a.fingerprint
                          FROM failed_job j
                          LEFT JOIN failure_analysis a ON a.failed_job_id = j.id
                          LEFT JOIN diagnosis d ON d.failed_job_id = j.id
                         WHERE j.workflow_run_id = :runId AND d.id IS NULL
                         ORDER BY j.id""")
                .param("runId", runId)
                .query((rs, row) -> new JobToDiagnose(
                        rs.getLong("id"),
                        rs.getLong("github_job_id"),
                        rs.getString("name"),
                        rs.getString("conclusion"),
                        textArray(rs.getArray("failed_steps")),
                        rs.getString("category") == null ? null : new FailureContext.Analysis(
                                rs.getString("category"),
                                rs.getString("failed_step"),
                                rs.getString("summary"),
                                rs.getString("error_excerpt"),
                                rs.getObject("error_start_line", Integer.class),
                                codeReferences(rs.getString("code_references")),
                                rs.getString("fingerprint"))))
                .list();
    }

    /** Idempotent: a job that already has a diagnosis keeps it. */
    void save(long failedJobId, DiagnosisResult result) {
        Diagnosis diagnosis = result.diagnosis();
        Usage usage = result.usage();
        jdbc.sql("""
                        INSERT INTO diagnosis (failed_job_id, source, category, root_cause, confidence, evidence,
                                               suggested_fix, patch, model, steps, tool_calls, input_tokens,
                                               output_tokens, cache_read_tokens, cache_write_tokens, cost_usd,
                                               latency_ms, agent_error)
                        VALUES (:jobId, :source, :category, :rootCause, :confidence, CAST(:evidence AS jsonb),
                                :fix, :patch, :model, :steps, CAST(:toolCalls AS jsonb), :input, :output,
                                :cacheRead, :cacheWrite, :cost, :latency, :agentError)
                        ON CONFLICT (failed_job_id) DO NOTHING""")
                .param("jobId", failedJobId)
                .param("source", result.source())
                .param("category", diagnosis.category())
                .param("rootCause", diagnosis.rootCause())
                .param("confidence", diagnosis.confidence().name())
                .param("evidence", JSON.writeValueAsString(diagnosis.evidence()))
                .param("fix", diagnosis.suggestedFix())
                .param("patch", diagnosis.patch().isBlank() ? null : diagnosis.patch())
                .param("model", result.model())
                .param("steps", result.steps())
                .param("toolCalls", JSON.writeValueAsString(result.toolCalls()))
                .param("input", usage.inputTokens())
                .param("output", usage.outputTokens())
                .param("cacheRead", usage.cacheReadTokens())
                .param("cacheWrite", usage.cacheWriteTokens())
                .param("cost", usage.costUsd())
                .param("latency", usage.latencyMs())
                .param("agentError", truncate(result.agentError()))
                .update();
    }

    void markDiagnosed(long runId) {
        jdbc.sql("UPDATE workflow_run SET status = 'DIAGNOSED', diagnosed_at = now(), error_message = NULL WHERE id = :id")
                .param("id", runId)
                .update();
    }

    /** Keeps DIAGNOSING; the unacknowledged queue entry is reclaimed and retried later. */
    void recordRetry(long runId, String error) {
        jdbc.sql("UPDATE workflow_run SET error_message = :error WHERE id = :id")
                .param("error", truncate(error))
                .param("id", runId)
                .update();
    }

    void markFailed(long runId, String error) {
        jdbc.sql("UPDATE workflow_run SET status = 'DIAGNOSIS_FAILED', error_message = :error WHERE id = :id")
                .param("error", truncate(error))
                .param("id", runId)
                .update();
    }

    boolean isIngested(long runId) {
        return jdbc.sql("SELECT status = 'INGESTED' FROM workflow_run WHERE id = :id")
                .param("id", runId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /** INGESTED runs whose diagnosis entry never made it onto the queue. */
    List<Long> findStuckInIngested(Duration olderThan, int limit) {
        return jdbc.sql("""
                        SELECT id FROM workflow_run
                         WHERE status = 'INGESTED' AND ingested_at < now() - make_interval(secs => :seconds)
                         ORDER BY ingested_at LIMIT :limit""")
                .param("seconds", olderThan.toSeconds())
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    private static List<FailureContext.CodeReference> codeReferences(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JSON.readValue(json, new TypeReference<List<FailureContext.CodeReference>>() {
        });
    }

    private static List<String> textArray(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static String truncate(String text) {
        return text == null || text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
