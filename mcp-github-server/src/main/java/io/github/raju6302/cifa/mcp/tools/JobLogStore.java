package io.github.raju6302.cifa.mcp.tools;

import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Job logs come from our database, never from GitHub: the worker redacts secrets before
 * storing them, and fetching the raw log again would bypass that.
 */
@Repository
class JobLogStore {

    private final JdbcClient jdbc;

    JobLogStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<StoredJobLog> find(long githubJobId) {
        return jdbc.sql("""
                        SELECT j.github_job_id, j.name, j.conclusion, j.failed_steps, j.log_text, j.log_truncated,
                               r.github_run_id, repo.full_name
                          FROM failed_job j
                          JOIN workflow_run r ON r.id = j.workflow_run_id
                          JOIN repository repo ON repo.id = r.repository_id
                         WHERE j.github_job_id = :jobId""")
                .param("jobId", githubJobId)
                .query((rs, row) -> new StoredJobLog(
                        rs.getLong("github_job_id"),
                        rs.getString("name"),
                        rs.getString("conclusion"),
                        strings(rs.getArray("failed_steps")),
                        rs.getString("full_name"),
                        rs.getLong("github_run_id"),
                        rs.getString("log_text"),
                        rs.getBoolean("log_truncated")))
                .optional();
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
