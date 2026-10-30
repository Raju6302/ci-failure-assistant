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

/** Read side for the dashboard. */
@Repository
class RunQueries {

    private static final String SUMMARY_SELECT = """
            SELECT r.id, repo.full_name, r.workflow_name, r.head_branch, r.head_sha, r.run_number, r.run_attempt,
                   r.event, r.conclusion, r.status, r.html_url, r.received_at, r.ingested_at, r.error_message,
                   (SELECT count(*) FROM failed_job j WHERE j.workflow_run_id = r.id) AS failed_job_count
              FROM workflow_run r
              JOIN repository repo ON repo.id = r.repository_id
            """;

    private final JdbcClient jdbc;

    RunQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
                        SELECT id, github_job_id, name, conclusion, failed_steps, log_bytes, log_truncated,
                               html_url, started_at, completed_at
                          FROM failed_job
                         WHERE workflow_run_id = :runId
                         ORDER BY started_at NULLS LAST, id""")
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
                        instant(rs, "completed_at")))
                .list();
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
                rs.getString("error_message"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> textArray(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
