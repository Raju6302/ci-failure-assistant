package io.github.raju6302.cifa.api.runs;

import io.github.raju6302.cifa.github.webhook.WorkflowRunEvent;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

/** Writes for the webhook → queue part of the pipeline. */
@Repository
public class RunStore {

    private final JdbcClient jdbc;

    RunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false if this delivery id was already handled */
    public boolean recordDelivery(String deliveryId, String event) {
        return jdbc.sql("""
                        INSERT INTO webhook_delivery (delivery_id, event) VALUES (:id, :event)
                        ON CONFLICT DO NOTHING""")
                .param("id", deliveryId)
                .param("event", event)
                .update() == 1;
    }

    public long upsertRepository(WorkflowRunEvent.Repository repo, Long installationId) {
        return jdbc.sql("""
                        INSERT INTO repository (github_id, full_name, installation_id)
                        VALUES (:githubId, :fullName, :installationId)
                        ON CONFLICT (github_id) DO UPDATE
                           SET full_name = EXCLUDED.full_name,
                               installation_id = COALESCE(EXCLUDED.installation_id, repository.installation_id),
                               updated_at = now()
                        RETURNING id""")
                .param("githubId", repo.id())
                .param("fullName", repo.fullName())
                .param("installationId", installationId)
                .query(Long.class)
                .single();
    }

    /** @return the new run's id, or empty if this run attempt is already stored */
    public Optional<Long> insertRun(long repositoryId, WorkflowRunEvent.WorkflowRun run) {
        Integer[] pullRequests = Arrays.stream(run.pullRequestNumbers()).boxed().toArray(Integer[]::new);
        return jdbc.sql("""
                        INSERT INTO workflow_run (repository_id, github_run_id, run_attempt, run_number, workflow_name,
                                                  event, head_branch, head_sha, conclusion, html_url, pull_request_numbers)
                        VALUES (:repositoryId, :runId, :attempt, :number, :name,
                                :event, :branch, :sha, :conclusion, :url, :pullRequests)
                        ON CONFLICT (repository_id, github_run_id, run_attempt) DO NOTHING
                        RETURNING id""")
                .param("repositoryId", repositoryId)
                .param("runId", run.id())
                .param("attempt", run.runAttempt())
                .param("number", run.runNumber())
                .param("name", run.name())
                .param("event", run.event())
                .param("branch", run.headBranch())
                .param("sha", run.headSha())
                .param("conclusion", run.conclusion())
                .param("url", run.htmlUrl())
                .param("pullRequests", new SqlArrayValue("int4", (Object[]) pullRequests))
                .query(Long.class)
                .optional();
    }

    /** Only moves RECEIVED → QUEUED, so a worker that already started is never set back. */
    public void markQueued(long runId) {
        jdbc.sql("UPDATE workflow_run SET status = 'QUEUED', queued_at = now() WHERE id = :id AND status = 'RECEIVED'")
                .param("id", runId)
                .update();
    }

    /** Runs that were stored but never made it onto the queue. */
    public List<Long> findStuckInReceived(Duration olderThan, int limit) {
        return jdbc.sql("""
                        SELECT id FROM workflow_run
                         WHERE status = 'RECEIVED' AND received_at < now() - make_interval(secs => :seconds)
                         ORDER BY received_at
                         LIMIT :limit""")
                .param("seconds", olderThan.toSeconds())
                .param("limit", limit)
                .query(Long.class)
                .list();
    }
}
