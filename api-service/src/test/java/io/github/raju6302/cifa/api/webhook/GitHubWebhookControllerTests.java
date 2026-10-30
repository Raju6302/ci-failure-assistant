package io.github.raju6302.cifa.api.webhook;

import static io.github.raju6302.cifa.api.WebhookTestSupport.failedRunPayload;
import static io.github.raju6302.cifa.api.WebhookTestSupport.queuedEntries;
import static io.github.raju6302.cifa.api.WebhookTestSupport.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.raju6302.cifa.api.TestcontainersConfiguration;
import io.github.raju6302.cifa.api.WebhookTestSupport;
import io.github.raju6302.cifa.common.RunQueue;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest(properties = {
        WebhookTestSupport.SECRET_PROPERTY,
        "cifa.relay.interval=PT1H"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GitHubWebhookControllerTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void reset() {
        WebhookTestSupport.reset(jdbc, redis);
    }

    @Test
    void storesAndQueuesAFailedRun() {
        assertThat(send("workflow_run", UUID.randomUUID().toString(), failedRunPayload()))
                .hasStatus(HttpStatus.ACCEPTED)
                .bodyJson().extractingPath("result").isEqualTo("accepted");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT status FROM workflow_run").query(String.class).single()).isEqualTo("QUEUED"));
        assertThat(jdbc.sql("""
                        SELECT r.github_run_id, r.conclusion, r.pull_request_numbers[1] AS pr, repo.full_name, repo.installation_id
                          FROM workflow_run r JOIN repository repo ON repo.id = r.repository_id""")
                .query().singleRow())
                .containsEntry("github_run_id", 18923456789L)
                .containsEntry("conclusion", "failure")
                .containsEntry("pr", 7)
                .containsEntry("full_name", "Raju6302/ci-failure-assistant")
                .containsEntry("installation_id", 61234567L);
        assertThat(redis.opsForStream().range(RunQueue.STREAM, org.springframework.data.domain.Range.unbounded()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getValue()).containsEntry(RunQueue.RUN_ID_FIELD, "1"));
    }

    @Test
    void ignoresARepeatedDelivery() {
        String deliveryId = UUID.randomUUID().toString();
        send("workflow_run", deliveryId, failedRunPayload());

        assertThat(send("workflow_run", deliveryId, failedRunPayload()))
                .bodyJson().extractingPath("result").isEqualTo("duplicate");
        assertThat(runCount()).isEqualTo(1);
        assertThat(queuedEntries(redis)).isEqualTo(1);
    }

    @Test
    void ignoresTheSameRunAttemptFromAnotherDelivery() {
        send("workflow_run", UUID.randomUUID().toString(), failedRunPayload());

        assertThat(send("workflow_run", UUID.randomUUID().toString(), failedRunPayload()))
                .bodyJson().extractingPath("result").isEqualTo("duplicate");
        assertThat(runCount()).isEqualTo(1);
    }

    @Test
    void storesARerunAsANewAttempt() {
        send("workflow_run", UUID.randomUUID().toString(), failedRunPayload());
        send("workflow_run", UUID.randomUUID().toString(), failedRunPayload().replace("\"run_attempt\": 1", "\"run_attempt\": 2"));

        assertThat(runCount()).isEqualTo(2);
    }

    @Test
    void ignoresSuccessfulAndInProgressRuns() {
        String success = failedRunPayload().replace("\"conclusion\": \"failure\"", "\"conclusion\": \"success\"");
        String requested = failedRunPayload().replace("\"action\": \"completed\"", "\"action\": \"requested\"");

        assertThat(send("workflow_run", UUID.randomUUID().toString(), success))
                .bodyJson().extractingPath("result").isEqualTo("ignored");
        assertThat(send("workflow_run", UUID.randomUUID().toString(), requested))
                .bodyJson().extractingPath("result").isEqualTo("ignored");
        assertThat(runCount()).isZero();
    }

    @Test
    void acceptsPingAndIgnoresOtherEvents() {
        assertThat(send("ping", UUID.randomUUID().toString(), "{\"zen\":\"Keep it logically awesome.\"}"))
                .hasStatus(HttpStatus.ACCEPTED);
        assertThat(send("push", UUID.randomUUID().toString(), "{}"))
                .bodyJson().extractingPath("result").isEqualTo("ignored");
    }

    @Test
    void rejectsBadSignatures() {
        String body = failedRunPayload();

        assertThat(mvc.post().uri("/webhooks/github")
                .header("X-GitHub-Event", "workflow_run")
                .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                .header("X-Hub-Signature-256", sign(body.replace("failure", "success")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/webhooks/github")
                .header("X-GitHub-Event", "workflow_run")
                .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(runCount()).isZero();
    }

    @Test
    void rejectsMalformedJson() {
        assertThat(send("workflow_run", UUID.randomUUID().toString(), "{not json"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    private MvcTestResult send(String event, String deliveryId, String body) {
        return mvc.post().uri("/webhooks/github")
                .header("X-GitHub-Event", event)
                .header("X-GitHub-Delivery", deliveryId)
                .header("X-Hub-Signature-256", sign(body))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private long runCount() {
        return jdbc.sql("SELECT count(*) FROM workflow_run").query(Long.class).single();
    }
}
