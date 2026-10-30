package io.github.raju6302.cifa.api.runs;

import io.github.raju6302.cifa.common.RunStatus;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Read side for the dashboard. */
@Repository
class RunQueries {

    // The run's first failed job (same order as the detail page) gives the category shown in the list.
    private static final String SUMMARY_SELECT = """
            SELECT r.id, repo.full_name, r.workflow_name, r.head_branch, r.head_sha, r.run_number, r.run_attempt,
                   r.event, r.conclusion, r.status, r.html_url, r.received_at, r.ingested_at, r.error_message,
                   (SELECT count(*) FROM failed_job j WHERE j.workflow_run_id = r.id) AS failed_job_count,
                   first_job.category, first_job.summary
              FROM workflow_run r
              JOIN repository repo ON repo.id = r.repository_id
              LEFT JOIN LATERAL (
                    SELECT a.category, a.summary
                      FROM failed_job j
                      LEFT JOIN failure_analysis a ON a.failed_job_id = j.id
                     WHERE j.workflow_run_id = r.id
                     ORDER BY j.started_at NULLS LAST, j.id
                     LIMIT 1) first_job ON true
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    RunQueries(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<RunSummary> latest(int limit) {
        return jdbc.sql(SUMMARY_SELECT + " ORDER BY r.received_at DESC, r.id DESC LIMIT :limit")
                .param("limit", limit)
                .query((rs, row) -> summary(rs))
                .list();
    }

    Optional<RunDetail> detail(long id) {
        return jdbc.sql(SUMMARY_SELECT + " WHERE r.id = :id")
                .param("id", id)
                .query((rs, row) -> summary(rs))
                .optional()
                .map(run -> new RunDetail(run, failedJobs(id)));
    }

    private List<RunDetail.FailedJob> failedJobs(long runId) {
        return jdbc.sql("""
                        SELECT j.id, j.github_job_id, j.name, j.conclusion, j.failed_steps, j.log_bytes, j.log_truncated,
                               j.html_url, j.started_at, j.completed_at,
                               a.category, a.failed_step, a.summary, a.error_excerpt, a.error_start_line, a.error_end_line,
                               a.code_references::text AS code_references, a.fingerprint,
                               d.source AS d_source, d.category AS d_category, d.root_cause, d.confidence,
                               d.evidence::text AS evidence, d.suggested_fix, d.patch, d.model, d.steps,
                               d.input_tokens, d.output_tokens, d.cost_usd, d.latency_ms, d.agent_error,
                               d.created_at AS diagnosed_at
                          FROM failed_job j
                          LEFT JOIN failure_analysis a ON a.failed_job_id = j.id
                          LEFT JOIN diagnosis d ON d.failed_job_id = j.id
                         WHERE j.workflow_run_id = :runId
                         ORDER BY j.started_at NULLS LAST, j.id""")
                .param("runId", runId)
                .query((rs, row) -> new RunDetail.FailedJob(
                        rs.getLong("id"),
                        rs.getLong("github_job_id"),
                        rs.getString("name"),
                        rs.getString("conclusion"),
                        textArray(rs.getArray("failed_steps")),
                        rs.getLong("log_bytes"),
                        rs.getBoolean("log_truncated"),
                        rs.getString("html_url"),
                        instant(rs, "started_at"),
                        instant(rs, "completed_at"),
                        analysis(rs),
                        diagnosis(rs)))
                .list();
    }

    private RunDetail.Analysis analysis(ResultSet rs) throws SQLException {
        if (rs.getString("category") == null) {
            return null;
        }
        return new RunDetail.Analysis(
                rs.getString("category"),
                rs.getString("failed_step"),
                rs.getString("summary"),
                rs.getString("error_excerpt"),
                rs.getObject("error_start_line", Integer.class),
                rs.getObject("error_end_line", Integer.class),
                List.of(json.readValue(rs.getString("code_references"), RunDetail.CodeReference[].class)),
                rs.getString("fingerprint"));
    }

    private RunDetail.Diagnosis diagnosis(ResultSet rs) throws SQLException {
        if (rs.getString("d_source") == null) {
            return null;
        }
        return new RunDetail.Diagnosis(
                rs.getString("d_source"),
                rs.getString("d_category"),
                rs.getString("root_cause"),
                rs.getString("confidence"),
                List.of(json.readValue(rs.getString("evidence"), RunDetail.Evidence[].class)),
                rs.getString("suggested_fix"),
                rs.getString("patch"),
                rs.getString("model"),
                rs.getInt("steps"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getDouble("cost_usd"),
                rs.getLong("latency_ms"),
                rs.getString("agent_error"),
                instant(rs, "diagnosed_at"));
    }

    private static RunSummary summary(ResultSet rs) throws SQLException {
        return new RunSummary(
                rs.getLong("id"),
                rs.getString("full_name"),
                rs.getString("workflow_name"),
                rs.getString("head_branch"),
                rs.getString("head_sha"),
                rs.getInt("run_number"),
                rs.getInt("run_attempt"),
                rs.getString("event"),
                rs.getString("conclusion"),
                RunStatus.valueOf(rs.getString("status")),
                rs.getInt("failed_job_count"),
                rs.getString("html_url"),
                instant(rs, "received_at"),
                instant(rs, "ingested_at"),
                rs.getString("error_message"),
                rs.getString("category"),
                rs.getString("summary"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> textArray(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
