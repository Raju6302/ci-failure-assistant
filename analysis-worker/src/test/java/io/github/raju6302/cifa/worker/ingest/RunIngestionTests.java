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
    static final String TEST_FAILURE_LOG = """
            2026-10-30T10:00:01.0000000Z ##[group]Run mvn -B verify
            2026-10-30T10:00:01.0000000Z ##[endgroup]
            2026-10-30T10:00:02.0000000Z Using token %s
            2026-10-30T10:00:03.0000000Z [ERROR] shop.PriceCalculatorTest.appliesDiscount -- Time elapsed: 0.02 s <<< FAILURE!
            2026-10-30T10:00:03.0000000Z org.opentest4j.AssertionFailedError: expected: <90.0> but was: <-900.0>
            2026-10-30T10:00:03.0000000Z \tat shop.PriceCalculatorTest.appliesDiscount(PriceCalculatorTest.java:13)
            2026-10-30T10:00:03.0000000Z [ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
            2026-10-30T10:00:04.0000000Z ##[error]Process completed with exit code 1.
            """.formatted(LEAKED_TOKEN);

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
    void runsTheWholePipelineFromTheQueueAndRedactsLogs() {
        stubJobs();
        stubAnnotations(301, "[]");
        stubLog(301, TEST_FAILURE_LOG);

        redis.opsForStream().add(StreamRecords.string(Map.of(RunQueue.RUN_ID_FIELD, Long.toString(runId)))
                .withStreamKey(RunQueue.STREAM));

        // The run goes through both pipeline stages: ingestion, then diagnosis (rule-based here,
        // since tests don't call Claude).
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(status()).isEqualTo("DIAGNOSED"));
        assertThat(jdbc.sql("SELECT source, category FROM diagnosis").query().singleRow())
                .containsEntry("source", "RULES")
                .containsEntry("category", "TEST_FAILURE");
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
    void analyzesEachJobRightAfterStoringIt() {
        stubJobs();
        stubAnnotations(301, """
                [{"path": ".github", "start_line": 8, "end_line": 8, "annotation_level": "failure", "title": "",
                  "message": "Process completed with exit code 1."},
                 {"path": "src/main/java/shop/PriceCalculator.java", "start_line": 7, "end_line": 7,
                  "annotation_level": "failure", "title": "Test report", "message": "Discount applied twice, key %s"}]"""
                .formatted(LEAKED_TOKEN));
        stubLog(301, TEST_FAILURE_LOG);

        assertThat(ingestion.ingest(runId)).isEqualTo(IngestOutcome.DONE);

        Map<String, Object> analysis = jdbc.sql("""
                        SELECT a.category, a.failed_step, a.summary, a.error_excerpt, a.error_start_line, a.error_end_line,
                               a.code_references::text AS refs, a.fingerprint, a.parser
                          FROM failure_analysis a JOIN failed_job j ON j.id = a.failed_job_id
                         WHERE j.github_job_id = 301""")
                .query().singleRow();
        assertThat(analysis)
                .containsEntry("category", "TEST_FAILURE")
                .containsEntry("failed_step", "Run tests")
                .containsEntry("summary", "shop.PriceCalculatorTest.appliesDiscount: "
                        + "org.opentest4j.AssertionFailedError: expected <90.0> but was <-900.0>")
                .containsEntry("parser", "maven")
                .containsEntry("error_start_line", 3)
                .containsEntry("error_end_line", 8);
        assertThat((String) analysis.get("error_excerpt")).contains("expected: <90.0> but was: <-900.0>").doesNotContain(LEAKED_TOKEN);
        assertThat((String) analysis.get("refs")).isEqualTo("""
                [{"line": 13, "path": "src/test/java/shop/PriceCalculatorTest.java"}, \
                {"line": 7, "path": "src/main/java/shop/PriceCalculator.java"}]""");
        assertThat((String) analysis.get("fingerprint")).matches("[0-9a-f]{64}");

        String annotations = jdbc.sql("SELECT annotations::text FROM failed_job").query(String.class).single();
        assertThat(annotations).contains("\"level\": \"failure\"", "Discount applied twice").doesNotContain(LEAKED_TOKEN);
    }

    @Test
    void reingestingReplacesTheAnalysis() {
        stubJobs();
        stubAnnotations(301, "[]");
        stubLog(301, TEST_FAILURE_LOG);

        ingestion.ingest(runId);
        jdbc.sql("UPDATE workflow_run SET status = 'QUEUED' WHERE id = :id").param("id", runId).update();
        ingestion.ingest(runId);

        assertThat(jdbc.sql("SELECT count(*) FROM failed_job").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM failure_analysis").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void keepsJobsCancelledByTheirTimeLimitButNotOtherCancels() {
        github.stubFor(get(urlPathEqualTo(JOBS_PATH)).willReturn(okJson("""
                {"total_count": 2, "jobs": [
                  {"id": 302, "name": "integration", "status": "completed", "conclusion": "cancelled",
                   "steps": [{"number": 1, "name": "Checkout", "conclusion": "success"},
                             {"number": 2, "name": "Integration tests", "conclusion": "cancelled"}]},
                  {"id": 303, "name": "lint", "status": "completed", "conclusion": "cancelled",
                   "steps": [{"number": 1, "name": "Lint", "conclusion": "cancelled"}]}
                ]}""")));
        stubAnnotations(302, """
                [{"path": ".github", "start_line": 1, "end_line": 1, "annotation_level": "failure", "title": "",
                  "message": "The job has exceeded the maximum execution time of 1m0s"},
                 {"path": ".github", "start_line": 6, "end_line": 6, "annotation_level": "failure", "title": "",
                  "message": "The operation was canceled."}]""");
        stubAnnotations(303, """
                [{"path": ".github", "start_line": 6, "end_line": 6, "annotation_level": "failure", "title": "",
                  "message": "The operation was canceled."}]""");
        stubLog(302, """
                2026-10-30T10:00:01.0000000Z ##[group]Run ./integration-test.sh
                2026-10-30T10:00:01.0000000Z ##[endgroup]
                2026-10-30T10:00:02.0000000Z Waiting for payments-service on localhost:9090...
                2026-10-30T10:01:02.0000000Z ##[error]The operation was canceled.
                """);

        assertThat(ingestion.ingest(runId)).isEqualTo(IngestOutcome.DONE);

        Map<String, Object> job = jdbc.sql("SELECT github_job_id, conclusion, failed_steps[1] AS step FROM failed_job")
                .query().singleRow();
        assertThat(job).containsEntry("github_job_id", 302L).containsEntry("conclusion", "cancelled")
                .containsEntry("step", "Integration tests");
        Map<String, Object> analysis = jdbc.sql("SELECT category, summary, parser FROM failure_analysis").query().singleRow();
        assertThat(analysis)
                .containsEntry("category", "INFRASTRUCTURE")
                .containsEntry("parser", "timeout")
                .containsEntry("summary", "Job timed out after 1m0s in \"Integration tests\"; "
                        + "last output: Waiting for payments-service on localhost:9090...");
        github.verify(0, getRequestedFor(urlEqualTo("/repos/octo/shop/actions/jobs/303/logs")));
    }

    @Test
    void ingestsJobsWhenAnnotationsAreNotAccessible() {
        stubJobs();
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/check-runs/301/annotations"))
                .willReturn(aResponse().withStatus(403).withBody("{\"message\":\"Resource not accessible by integration\"}")));
        stubLog(301, TEST_FAILURE_LOG);

        assertThat(ingestion.ingest(runId)).isEqualTo(IngestOutcome.DONE);

        assertThat(status()).isEqualTo("INGESTED");
        assertThat(jdbc.sql("SELECT category FROM failure_analysis").query(String.class).single()).isEqualTo("TEST_FAILURE");
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
        stubAnnotations(301, "[]");
        github.stubFor(get(urlEqualTo("/repos/octo/shop/actions/jobs/301/logs")).willReturn(aResponse().withStatus(410)));

        assertThat(ingestion.ingest(runId)).isEqualTo(IngestOutcome.DONE);

        assertThat(status()).isEqualTo("INGESTED");
        assertThat(jdbc.sql("SELECT log_text FROM failed_job").query(String.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT category, summary FROM failure_analysis").query().singleRow())
                .containsEntry("category", "UNKNOWN")
                .containsEntry("summary", "No log or error annotations available; failed step: \"Run tests\"");
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

    private void stubAnnotations(long jobId, String body) {
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/check-runs/%d/annotations".formatted(jobId))).willReturn(okJson(body)));
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
