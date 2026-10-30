package io.github.raju6302.cifa.api.runs;

import static io.github.raju6302.cifa.api.WebhookTestSupport.failedRunPayload;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.raju6302.cifa.api.TestcontainersConfiguration;
import io.github.raju6302.cifa.api.WebhookTestSupport;
import io.github.raju6302.cifa.api.webhook.WorkflowRunWebhookHandler;
import io.github.raju6302.cifa.github.GitHubJson;
import io.github.raju6302.cifa.github.webhook.WorkflowRunEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest(properties = {
        WebhookTestSupport.SECRET_PROPERTY,
        "cifa.relay.interval=PT1H"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RunsControllerTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    WorkflowRunWebhookHandler handler;

    @BeforeEach
    void reset() {
        WebhookTestSupport.reset(jdbc, redis);
    }

    @Test
    void listsRunsNewestFirst() {
        receive(failedRunPayload());
        receive(failedRunPayload().replace("\"run_attempt\": 1", "\"run_attempt\": 2"));

        assertThat(mvc.get().uri("/api/runs"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.length()", length -> length.assertThat().isEqualTo(2))
                .hasPathSatisfying("$[0].runAttempt", attempt -> attempt.assertThat().isEqualTo(2))
                .hasPathSatisfying("$[0].repository", repo -> repo.assertThat().isEqualTo("Raju6302/ci-failure-assistant"))
                .hasPathSatisfying("$[0].failedJobCount", count -> count.assertThat().isEqualTo(0));
    }

    @Test
    void showsARunWithItsFailedJobs() {
        long runId = receive(failedRunPayload());
        jdbc.sql("""
                        INSERT INTO failed_job (workflow_run_id, github_job_id, name, conclusion, failed_steps, log_bytes,
                                                log_truncated, started_at, completed_at)
                        VALUES (:runId, 555, 'java-test-failure', 'failure', '{"Run tests"}', 48213, true,
                                '2026-10-30T09:59:10Z', '2026-10-30T09:59:58Z')""")
                .param("runId", runId)
                .update();

        assertThat(mvc.get().uri("/api/runs/{id}", runId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.id", id -> id.assertThat().isEqualTo((int) runId))
                .hasPathSatisfying("$.failedJobCount", count -> count.assertThat().isEqualTo(1))
                .hasPathSatisfying("$.jobs[0].failedSteps[0]", step -> step.assertThat().isEqualTo("Run tests"))
                .hasPathSatisfying("$.jobs[0].logTruncated", truncated -> truncated.assertThat().isEqualTo(true))
                .hasPathSatisfying("$.jobs[0].startedAt", started -> started.assertThat().isEqualTo("2026-10-30T09:59:10Z"));
    }

    @Test
    void returns404ForUnknownRuns() {
        assertThat(mvc.get().uri("/api/runs/999")).hasStatus(HttpStatus.NOT_FOUND);
    }

    private long receive(String payload) {
        handler.handle(java.util.UUID.randomUUID().toString(), GitHubJson.mapper().readValue(payload, WorkflowRunEvent.class));
        return jdbc.sql("SELECT max(id) FROM workflow_run").query(Long.class).single();
    }
}
