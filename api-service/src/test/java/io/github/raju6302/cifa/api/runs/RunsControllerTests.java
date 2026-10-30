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
    void showsEachJobsAnalysisOrNullWhenThereIsNone() {
        long runId = receive(failedRunPayload());
        long analyzed = insertJob(runId, 555, "java-test-failure", "2026-10-30T09:59:10Z");
        insertJob(runId, 556, "lint", "2026-10-30T09:59:20Z");
        insertAnalysis(analyzed, "TEST_FAILURE", "PriceCalculatorTest.appliesPercentageDiscount: expected <90.0> but was <-900.0>");

        assertThat(mvc.get().uri("/api/runs/{id}", runId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.category", category -> category.assertThat().isEqualTo("TEST_FAILURE"))
                .hasPathSatisfying("$.jobs[0].analysis.category", category -> category.assertThat().isEqualTo("TEST_FAILURE"))
                .hasPathSatisfying("$.jobs[0].analysis.failedStep", step -> step.assertThat().isEqualTo("Build and test"))
                .hasPathSatisfying("$.jobs[0].analysis.summary", summary -> summary.assertThat().asString()
                        .contains("appliesPercentageDiscount"))
                .hasPathSatisfying("$.jobs[0].analysis.excerpt", excerpt -> excerpt.assertThat().asString()
                        .startsWith("[ERROR] Failures:"))
                .hasPathSatisfying("$.jobs[0].analysis.excerptStartLine", line -> line.assertThat().isEqualTo(208))
                .hasPathSatisfying("$.jobs[0].analysis.excerptEndLine", line -> line.assertThat().isEqualTo(209))
                .hasPathSatisfying("$.jobs[0].analysis.codeReferences[0].path", path -> path.assertThat()
                        .isEqualTo("sandbox-repos/java-test-failure/src/test/java/shop/PriceCalculatorTest.java"))
                .hasPathSatisfying("$.jobs[0].analysis.codeReferences[0].line", line -> line.assertThat().isEqualTo(13))
                .hasPathSatisfying("$.jobs[0].analysis.codeReferences[1].line", line -> line.assertThat().isNull())
                .hasPathSatisfying("$.jobs[0].analysis.fingerprint", fingerprint -> fingerprint.assertThat().isEqualTo("f".repeat(64)))
                .hasPathSatisfying("$.jobs[1].name", name -> name.assertThat().isEqualTo("lint"))
                .hasPathSatisfying("$.jobs[1].analysis", analysis -> analysis.assertThat().isNull());
    }

    @Test
    void showsEachJobsDiagnosisOrNullWhenThereIsNone() {
        long runId = receive(failedRunPayload());
        long diagnosed = insertJob(runId, 555, "java-test-failure", "2026-10-30T09:59:10Z");
        insertJob(runId, 556, "lint", "2026-10-30T09:59:20Z");
        jdbc.sql("""
                        INSERT INTO diagnosis (failed_job_id, source, category, root_cause, confidence, evidence,
                                               suggested_fix, patch, model, steps, input_tokens, output_tokens, cost_usd, latency_ms)
                        VALUES (:job, 'AGENT', 'TEST_FAILURE', 'applyDiscount ignores the percent scale.', 'HIGH',
                                '[{"source": "file", "path": "src/main/java/shop/PriceCalculator.java", "line": 7,
                                   "quote": "return price * (1 - percent);"}]',
                                'Divide percent by 100.', NULL, 'claude-opus-5-5', 3, 12000, 900, 0.0660, 41000)""")
                .param("job", diagnosed)
                .update();

        assertThat(mvc.get().uri("/api/runs/{id}", runId))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.jobs[0].diagnosis.source", source -> source.assertThat().isEqualTo("AGENT"))
                .hasPathSatisfying("$.jobs[0].diagnosis.confidence", confidence -> confidence.assertThat().isEqualTo("HIGH"))
                .hasPathSatisfying("$.jobs[0].diagnosis.evidence[0].line", line -> line.assertThat().isEqualTo(7))
                .hasPathSatisfying("$.jobs[0].diagnosis.patch", patch -> patch.assertThat().isNull())
                .hasPathSatisfying("$.jobs[0].diagnosis.costUsd", cost -> cost.assertThat().isEqualTo(0.066))
                .hasPathSatisfying("$.jobs[0].diagnosis.steps", steps -> steps.assertThat().isEqualTo(3))
                .hasPathSatisfying("$.jobs[1].diagnosis", diagnosis -> diagnosis.assertThat().isNull());
    }

    @Test
    void listsTheCategoryAndSummaryOfEachRunsFirstFailedJob() {
        long older = receive(failedRunPayload());
        long first = insertJob(older, 555, "java-compile-error", "2026-10-30T09:59:10Z");
        long second = insertJob(older, 556, "java-test-failure", "2026-10-30T09:59:20Z");
        insertAnalysis(second, "TEST_FAILURE", "a test failed");
        insertAnalysis(first, "COMPILE_ERROR", "InvoiceService.java:9: cannot find symbol: method price()");
        receive(failedRunPayload().replace("\"run_attempt\": 1", "\"run_attempt\": 2"));

        assertThat(mvc.get().uri("/api/runs"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$[0].category", category -> category.assertThat().isNull())
                .hasPathSatisfying("$[0].summary", summary -> summary.assertThat().isNull())
                .hasPathSatisfying("$[1].category", category -> category.assertThat().isEqualTo("COMPILE_ERROR"))
                .hasPathSatisfying("$[1].summary", summary -> summary.assertThat()
                        .isEqualTo("InvoiceService.java:9: cannot find symbol: method price()"));
    }

    @Test
    void returns404ForUnknownRuns() {
        assertThat(mvc.get().uri("/api/runs/999")).hasStatus(HttpStatus.NOT_FOUND);
    }

    private long insertJob(long runId, long githubJobId, String name, String startedAt) {
        return jdbc.sql("""
                        INSERT INTO failed_job (workflow_run_id, github_job_id, name, conclusion, started_at)
                        VALUES (:runId, :jobId, :name, 'failure', CAST(:startedAt AS timestamptz))
                        RETURNING id""")
                .param("runId", runId)
                .param("jobId", githubJobId)
                .param("name", name)
                .param("startedAt", startedAt)
                .query(Long.class)
                .single();
    }

    private void insertAnalysis(long failedJobId, String category, String summary) {
        jdbc.sql("""
                        INSERT INTO failure_analysis (failed_job_id, category, failed_step, summary, error_excerpt,
                                                      error_start_line, error_end_line, code_references, fingerprint, parser)
                        VALUES (:jobId, :category, 'Build and test', :summary,
                                E'[ERROR] Failures:\\n[ERROR]   PriceCalculatorTest.appliesPercentageDiscount:13', 208, 209,
                                '[{"path": "sandbox-repos/java-test-failure/src/test/java/shop/PriceCalculatorTest.java", "line": 13},
                                  {"path": "pom.xml", "line": null}]',
                                repeat('f', 64), 'maven')""")
                .param("jobId", failedJobId)
                .param("category", category)
                .param("summary", summary)
                .update();
    }

    private long receive(String payload) {
        handler.handle(java.util.UUID.randomUUID().toString(), GitHubJson.mapper().readValue(payload, WorkflowRunEvent.class));
        return jdbc.sql("SELECT max(id) FROM workflow_run").query(Long.class).single();
    }
}
