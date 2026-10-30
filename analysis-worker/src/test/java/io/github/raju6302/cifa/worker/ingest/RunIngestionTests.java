package io.github.raju6302.cifa.worker.ingest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.raju6302.cifa.common.RunQueue;
import io.github.raju6302.cifa.worker.TestcontainersConfiguration;
import io.github.raju6302.cifa.worker.redaction.SecretRedactor;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"cifa.github.token=test-token", "cifa.ingestion.max-attempts=3",
        "cifa.ingestion.reclaim-interval=PT1H"})
@Import(TestcontainersConfiguration.class)
class RunIngestionTests {

    static final String JOBS_PATH = "/repos/octo/shop/actions/runs/9001/attempts/1/jobs";
    static final String LEAKED_TOKEN = "ghp" + "_" + "Zz9Yy8Xx7Ww6Vv5Uu4Tt3Ss2Rr1Qq0Pp9Oo8";

    @RegisterExtension
    static WireMockExtension github = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void githubUrl(DynamicPropertyRegistry registry) {
        registry.add("cifa.github.api-url", github::baseUrl);
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RunIngestionService ingestion;

    long runId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE failed_job, workflow_run, repository RESTART IDENTITY CASCADE").update();
        long repoId = jdbc.sql("INSERT INTO repository (github_id, full_name) VALUES (1, 'octo/shop') RETURNING id")
                .query(Long.class).single();
        runId = jdbc.sql("""
                        INSERT INTO workflow_run (repository_id, github_run_id, run_attempt, run_number, workflow_name,
                                                  event, head_branch, head_sha, conclusion, html_url, status)
                        VALUES (:repoId, 9001, 1, 12, 'CI', 'push', 'main', 'abc123', 'failure',
                                'https://github.com/octo/shop/actions/runs/9001', 'QUEUED')
                        RETURNING id""")
                .param("repoId", repoId)
                .query(Long.class).single();
    }

    @Test
    void ingestsFailedJobsFromTheQueueAndRedactsLogs() {
        stubJobs();
        stubLog(301, "Run mvn verify\nUsing token " + LEAKED_TOKEN + "\n[ERROR] Tests run: 3, Failures: 1\n");

        redis.opsForStream().add(StreamRecords.string(Map.of(RunQueue.RUN_ID_FIELD, Long.toString(runId)))
                .withStreamKey(RunQueue.STREAM));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(status()).isEqualTo("INGESTED"));
        Map<String, Object> job = jdbc.sql("SELECT * FROM failed_job").query().singleRow();
        assertThat(job)
                .containsEntry("github_job_id", 301L)
                .containsEntry("name", "test")
                .containsEntry("log_truncated", false);
        assertThat((String) job.get("log_text"))
                .contains("[ERROR] Tests run: 3, Failures: 1")
                .contains(SecretRedactor.MASK)
                .doesNotContain(LEAKED_TOKEN);
        assertThat(jdbc.sql("SELECT failed_steps[1] FROM failed_job").query(String.class).single()).isEqualTo("Run tests");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                redis.opsForStream().pending(RunQueue.STREAM, RunQueue.GROUP, Range.unbounded(), 10).size()).isZero());
    }

    @Test
    void retriesTemporaryErrorsThenGivesUp() {
        github.stubFor(get(urlPathEqualTo(JOBS_PATH)).willReturn(aResponse().withStatus(503)));

        assertThat(ingestion.ingest(runId)).isEqualTo(IngestOutcome.RETRY);
        assertThat(status()).isEqualTo("RECEIVED");
        assertThat(ingestion.ingest(runId)).isEqualTo(IngestOutcome.RETRY);
        assertThat(ingestion.ingest(runId)).isEqualTo(IngestOutcome.DONE);

        assertThat(status()).isEqualTo("INGEST_FAILED");
        assertThat(jdbc.sql("SELECT ingest_attempts FROM workflow_run").query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT error_message FROM workflow_run").query(String.class).single()).contains("503");
    }

    @Test
    void failsImmediatelyOnPermanentErrors() {
        github.stubFor(get(urlPathEqualTo(JOBS_PATH))
                .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));

        assertThat(ingestion.ingest(runId)).isEqualTo(IngestOutcome.DONE);
        assertThat(status()).isEqualTo("INGEST_FAILED");
    }

    @Test
    void storesJobsEvenWhenTheirLogHasExpired() {
        stubJobs();
        github.stubFor(get(urlEqualTo("/repos/octo/shop/actions/jobs/301/logs")).willReturn(aResponse().withStatus(410)));

        assertThat(ingestion.ingest(runId)).isEqualTo(IngestOutcome.DONE);

        assertThat(status()).isEqualTo("INGESTED");
        assertThat(jdbc.sql("SELECT log_text FROM failed_job").query(String.class).optional()).isEmpty();
    }

    @Test
    void skipsRunsThatAreAlreadyIngested() {
        jdbc.sql("UPDATE workflow_run SET status = 'INGESTED' WHERE id = :id").param("id", runId).update();

        assertThat(ingestion.ingest(runId)).isEqualTo(IngestOutcome.DONE);
        github.verify(0, getRequestedFor(urlPathEqualTo(JOBS_PATH)));
    }

    private void stubJobs() {
        github.stubFor(get(urlPathEqualTo(JOBS_PATH)).willReturn(okJson("""
                {"total_count": 2, "jobs": [
                  {"id": 300, "name": "lint", "status": "completed", "conclusion": "success", "steps": []},
                  {"id": 301, "name": "test", "status": "completed", "conclusion": "failure",
                   "html_url": "https://github.com/octo/shop/actions/runs/9001/job/301",
                   "started_at": "2026-10-30T10:00:00Z", "completed_at": "2026-10-30T10:02:00Z",
                   "steps": [{"number": 1, "name": "Checkout", "conclusion": "success"},
                             {"number": 2, "name": "Run tests", "conclusion": "failure"}]}
                ]}""")));
    }

    private void stubLog(long jobId, String body) {
        github.stubFor(get(urlEqualTo("/repos/octo/shop/actions/jobs/%d/logs".formatted(jobId)))
                .willReturn(aResponse().withStatus(302).withHeader("Location", github.baseUrl() + "/blob/" + jobId)));
        github.stubFor(get(urlEqualTo("/blob/" + jobId)).willReturn(aResponse().withBody(body)));
    }

    private String status() {
        return jdbc.sql("SELECT status FROM workflow_run WHERE id = :id").param("id", runId).query(String.class).single();
    }
}
