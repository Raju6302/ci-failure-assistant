package io.github.raju6302.cifa.api.queue;

import static io.github.raju6302.cifa.api.WebhookTestSupport.failedRunPayload;
import static io.github.raju6302.cifa.api.WebhookTestSupport.queuedEntries;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.raju6302.cifa.api.TestcontainersConfiguration;
import io.github.raju6302.cifa.api.WebhookTestSupport;
import io.github.raju6302.cifa.api.runs.RunStore;
import io.github.raju6302.cifa.github.GitHubJson;
import io.github.raju6302.cifa.github.webhook.WorkflowRunEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {"cifa.relay.interval=PT1H", "cifa.relay.min-age=PT30S"})
@Import(TestcontainersConfiguration.class)
class PendingRunRelayTests {

    @Autowired
    PendingRunRelay relay;

    @Autowired
    RunStore store;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void reset() {
        WebhookTestSupport.reset(jdbc, redis);
    }

    @Test
    void requeuesRunsThatNeverReachedTheQueue() {
        long stuck = insertRun(1, "5 minutes");
        long fresh = insertRun(2, "1 second");

        relay.requeueStuckRuns();

        assertThat(status(stuck)).isEqualTo("QUEUED");
        assertThat(status(fresh)).isEqualTo("RECEIVED");
        assertThat(queuedEntries(redis)).isEqualTo(1);
    }

    /** Stores a run directly, as if Redis had been down when the webhook arrived. */
    private long insertRun(int attempt, String age) {
        WorkflowRunEvent event = GitHubJson.mapper().readValue(
                failedRunPayload().replace("\"run_attempt\": 1", "\"run_attempt\": " + attempt), WorkflowRunEvent.class);
        long repoId = store.upsertRepository(event.repository(), event.installationId());
        long runId = store.insertRun(repoId, event.workflowRun()).orElseThrow();
        jdbc.sql("UPDATE workflow_run SET received_at = now() - CAST(:age AS interval) WHERE id = :id")
                .param("age", age)
                .param("id", runId)
                .update();
        return runId;
    }

    private String status(long runId) {
        return jdbc.sql("SELECT status FROM workflow_run WHERE id = :id").param("id", runId).query(String.class).single();
    }
}
