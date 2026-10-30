package io.github.raju6302.cifa.worker.rag;

import io.github.raju6302.cifa.worker.diagnosis.FailureContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Remembers each diagnosed failure so a recurring one can be recognized: first by exact
 * error fingerprint (from Phase 2), then by embedding similarity. Once Phase 6 links a
 * failure to the commit that fixed it, {@code resolution} tells the agent what worked.
 */
public class FailureMemory {

    private final JdbcClient jdbc;
    private final Embedder embedder;
    private final RagProperties properties;

    public FailureMemory(JdbcClient jdbc, Embedder embedder, RagProperties properties) {
        this.jdbc = jdbc;
        this.embedder = embedder;
        this.properties = properties;
    }

    public void remember(long repositoryId, long failedJobId, String fingerprint, String category, String summary,
                         String rootCause) {
        jdbc.sql("""
                        INSERT INTO failure_memory (repository_id, failed_job_id, fingerprint, category, summary,
                                                    root_cause, embedding)
                        VALUES (:repo, :job, :fingerprint, :category, :summary, :rootCause, CAST(:embedding AS vector))
                        ON CONFLICT (failed_job_id) DO NOTHING""")
                .param("repo", repositoryId).param("job", failedJobId).param("fingerprint", fingerprint)
                .param("category", category).param("summary", summary).param("rootCause", rootCause)
                .param("embedding", Embedder.toVectorLiteral(embedder.embed(memoryText(category, summary))))
                .update();
    }

    /** Past failures in the same repository that look like this one, most relevant first. */
    public List<FailureContext.PastFailure> similar(long repositoryId, long failedJobId, String fingerprint,
                                                    String category, String summary) {
        List<FailureContext.PastFailure> results = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        if (fingerprint != null) {
            results.addAll(query("""
                    WHERE m.repository_id = :repo AND m.failed_job_id <> :job AND m.fingerprint = :fingerprint
                    ORDER BY m.created_at DESC LIMIT :limit""", repositoryId, failedJobId, fingerprint, null, true));
            results.forEach(r -> seen.add(r.failedJobId()));
        }
        if (results.size() < properties.memoryResults()) {
            String vector = Embedder.toVectorLiteral(embedder.embed(memoryText(category, summary)));
            for (FailureContext.PastFailure past : query("""
                    WHERE m.repository_id = :repo AND m.failed_job_id <> :job
                      AND (m.embedding <=> CAST(:vector AS vector)) < :maxDistance
                    ORDER BY m.embedding <=> CAST(:vector AS vector) LIMIT :limit""", repositoryId, failedJobId, null, vector, false)) {
                if (seen.add(past.failedJobId()) && results.size() < properties.memoryResults()) {
                    results.add(past);
                }
            }
        }
        return results;
    }

    private List<FailureContext.PastFailure> query(String where, long repositoryId, long failedJobId, String fingerprint,
                                                   String vector, boolean sameFingerprint) {
        var statement = jdbc.sql("""
                        SELECT m.failed_job_id, r.head_sha, to_char(m.created_at, 'YYYY-MM-DD') AS created,
                               m.root_cause, m.resolution
                          FROM failure_memory m
                          JOIN failed_job j ON j.id = m.failed_job_id
                          JOIN workflow_run r ON r.id = j.workflow_run_id
                        """ + where)
                .param("repo", repositoryId).param("job", failedJobId).param("limit", properties.memoryResults());
        if (fingerprint != null) {
            statement = statement.param("fingerprint", fingerprint);
        }
        if (vector != null) {
            statement = statement.param("vector", vector).param("maxDistance", properties.memoryMaxDistance());
        }
        return statement.query((rs, row) -> new FailureContext.PastFailure(rs.getLong("failed_job_id"),
                        rs.getString("head_sha"), rs.getString("created"), sameFingerprint, rs.getString("root_cause"),
                        rs.getString("resolution")))
                .list();
    }

    private static String memoryText(String category, String summary) {
        return category + ": " + summary;
    }
}
