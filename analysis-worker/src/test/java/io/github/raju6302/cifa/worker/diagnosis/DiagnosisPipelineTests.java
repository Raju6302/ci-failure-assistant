package io.github.raju6302.cifa.worker.diagnosis;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.raju6302.cifa.worker.TestcontainersConfiguration;
import io.github.raju6302.cifa.worker.logs.FailureAnalysis;
import io.github.raju6302.cifa.worker.logs.FailureAnalyzer;
import io.github.raju6302.cifa.worker.logs.Fixtures;
import io.github.raju6302.cifa.worker.rag.LocalDirectorySource;
import io.github.raju6302.cifa.worker.rag.RepositorySource;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phases 2, 4 and 5 together: a real CI log analyzed by the rule-based parser, code
 * retrieved from this repository, and the agent (real SDK, scripted fake Claude API)
 * producing the stored diagnosis. Also covers retries, the rule-based fallback and
 * recognizing a recurring failure.
 */
@SpringBootTest(properties = {"cifa.diagnosis.agent-enabled=true", "cifa.diagnosis.max-attempts=3",
        "cifa.ingestion.reclaim-interval=PT1H"})
@Import(TestcontainersConfiguration.class)
class DiagnosisPipelineTests {

    @RegisterExtension
    static WireMockExtension claude = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String SUBMIT = """
            {"id": "msg_2", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
             "content": [{"type": "tool_use", "id": "toolu_2", "name": "submit_diagnosis", "input": {
               "rootCause": "InvoiceService calls LineItem.price(), but the record component is unitPrice().",
               "category": "COMPILE_ERROR", "confidence": "HIGH",
               "evidence": [{"source": "log", "path": "", "line": 0, "quote": "cannot find symbol"}],
               "suggestedFix": "Call item.unitPrice().", "patch": ""}}],
             "stop_reason": "tool_use", "stop_sequence": null,
             "usage": {"input_tokens": 4000, "output_tokens": 300, "cache_read_input_tokens": 0, "cache_creation_input_tokens": 0}}""";

    @TestBean
    AnthropicClient anthropicClient;

    @TestBean
    AgentTools agentTools;

    @TestBean
    RepositorySource repositorySource;

    static AnthropicClient anthropicClient() {
        return AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(claude.baseUrl()).maxRetries(0).build();
    }

    static AgentTools agentTools() {
        return new DiagnosisAgentTest.FakeTools();
    }

    static RepositorySource repositorySource() {
        return new LocalDirectorySource(Path.of("..").toAbsolutePath().normalize());
    }

    @Autowired
    DiagnosisService diagnosis;

    @Autowired
    JdbcClient jdbc;

    long repositoryId;

    @BeforeEach
    void setUp() {
        claude.resetAll();
        jdbc.sql("TRUNCATE failure_memory, code_chunk, code_file, failed_job, workflow_run, repository RESTART IDENTITY CASCADE").update();
        repositoryId = jdbc.sql("INSERT INTO repository (github_id, full_name) VALUES (7, 'Raju6302/ci-failure-assistant') RETURNING id")
                .query(Long.class).single();
    }

    @Test
    void diagnosesWithTheLogAnalysisAndRetrievedCode() {
        claude.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(okJson(SUBMIT)));
        long runId = ingestedRun(1, "java-compile-error");

        assertThat(diagnosis.diagnose(runId)).isEqualTo(DiagnosisService.Outcome.DONE);

        assertThat(status(runId)).isEqualTo("DIAGNOSED");
        assertThat(jdbc.sql("SELECT source, category, confidence, model, cost_usd::float AS cost FROM diagnosis").query().singleRow())
                .containsEntry("source", "AGENT")
                .containsEntry("category", "COMPILE_ERROR")
                .containsEntry("confidence", "HIGH")
                .containsEntry("model", "claude-opus-5-5")
                .containsEntry("cost", 0.022);
        // The first message carries the Phase 2 analysis and the code RAG retrieved.
        claude.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("cannot find symbol")))
                .withRequestBody(matchingJsonPath("$.messages[0].content",
                        containing("<snippet path=\"sandbox-repos/java-compile-error/src/main/java/shop/InvoiceService.java\""))));
        assertThat(jdbc.sql("SELECT count(*) FROM failure_memory").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void recognizesARecurringFailure() {
        claude.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(okJson(SUBMIT)));
        diagnosis.diagnose(ingestedRun(1, "java-compile-error"));
        claude.resetRequests();

        diagnosis.diagnose(ingestedRun(2, "java-compile-error"));

        claude.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("<similar_past_failures>")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("same error signature")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("unitPrice()"))));
    }

    @Test
    void retriesTemporaryErrorsThenFallsBackToRules() {
        claude.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(aResponse().withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\": \"error\", \"error\": {\"type\": \"rate_limit_error\", \"message\": \"slow down\"}}")));
        long runId = ingestedRun(1, "java-test-failure");

        assertThat(diagnosis.diagnose(runId)).isEqualTo(DiagnosisService.Outcome.RETRY);
        assertThat(status(runId)).isEqualTo("DIAGNOSING");
        assertThat(diagnosis.diagnose(runId)).as("not taken over while the attempt is fresh")
                .isEqualTo(DiagnosisService.Outcome.DONE);
        assertThat(attempts(runId)).isEqualTo(1);

        makeAttemptStale(runId);
        assertThat(diagnosis.diagnose(runId)).isEqualTo(DiagnosisService.Outcome.RETRY);
        makeAttemptStale(runId);
        assertThat(diagnosis.diagnose(runId)).isEqualTo(DiagnosisService.Outcome.DONE);

        assertThat(status(runId)).isEqualTo("DIAGNOSED");
        assertThat(jdbc.sql("SELECT source, confidence, agent_error FROM diagnosis").query().singleRow())
                .containsEntry("source", "RULES")
                .containsEntry("confidence", "LOW")
                .satisfies(row -> assertThat((String) row.get("agent_error")).contains("temporarily unavailable"));
    }

    private long ingestedRun(int number, String scenario) {
        long runId = jdbc.sql("""
                        INSERT INTO workflow_run (repository_id, github_run_id, run_attempt, run_number, workflow_name, event,
                                                  head_branch, head_sha, conclusion, html_url, status, ingested_at)
                        VALUES (:repo, :n, 1, :n, 'Sandbox failures', 'workflow_dispatch', 'main', 'abc123', 'failure',
                                'https://github.com/Raju6302/ci-failure-assistant/actions/runs/1', 'INGESTED', now())
                        RETURNING id""")
                .param("repo", repositoryId).param("n", number).query(Long.class).single();
        long jobId = jdbc.sql("""
                        INSERT INTO failed_job (workflow_run_id, github_job_id, name, conclusion, failed_steps, log_text)
                        VALUES (:run, :jobId, :name, 'failure', '{"Build and test"}', :log) RETURNING id""")
                .param("run", runId).param("jobId", 1000 + number).param("name", scenario)
                .param("log", Fixtures.log(scenario)).query(Long.class).single();
        FailureAnalysis analysis = new FailureAnalyzer().analyze(Fixtures.evidence(scenario));
        jdbc.sql("""
                        INSERT INTO failure_analysis (failed_job_id, category, failed_step, summary, error_excerpt,
                                                      error_start_line, error_end_line, code_references, fingerprint, parser)
                        VALUES (:job, :category, :step, :summary, :excerpt, :start, :end, CAST(:refs AS jsonb), :fp, :parser)""")
                .param("job", jobId).param("category", analysis.category().name()).param("step", analysis.failedStep())
                .param("summary", analysis.summary()).param("excerpt", analysis.excerpt())
                .param("start", analysis.excerptStartLine()).param("end", analysis.excerptEndLine())
                .param("refs", JsonMapper.builder().build().writeValueAsString(analysis.codeReferences()))
                .param("fp", analysis.fingerprint()).param("parser", analysis.parser())
                .update();
        return runId;
    }

    private void makeAttemptStale(long runId) {
        jdbc.sql("UPDATE workflow_run SET diagnosis_started_at = now() - interval '1 hour' WHERE id = :id").param("id", runId).update();
    }

    private String status(long runId) {
        return jdbc.sql("SELECT status FROM workflow_run WHERE id = :id").param("id", runId).query(String.class).single();
    }

    private int attempts(long runId) {
        return jdbc.sql("SELECT diagnosis_attempts FROM workflow_run WHERE id = :id").param("id", runId).query(Integer.class).single();
    }
}
