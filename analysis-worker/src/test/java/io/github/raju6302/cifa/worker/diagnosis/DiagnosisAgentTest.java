package io.github.raju6302.cifa.worker.diagnosis;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Runs the real Anthropic SDK against a scripted fake of the Messages API, so the requests
 * we send (tools, caching, fallbacks, tool results, preserved thinking) are checked as JSON.
 */
class DiagnosisAgentTest {

    @RegisterExtension
    static WireMockExtension claude = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String SUBMIT = """
            {"type": "tool_use", "id": "toolu_2", "name": "submit_diagnosis", "input": {
              "rootCause": "applyDiscount multiplies by (1 - percent) instead of (1 - percent / 100).",
              "category": "TEST_FAILURE", "confidence": "HIGH",
              "evidence": [{"source": "file", "path": "src/main/java/shop/PriceCalculator.java", "line": 7,
                            "quote": "return price * (1 - percent);"}],
              "suggestedFix": "Divide percent by 100.", "patch": ""}}""";

    AnthropicClient client;
    FakeTools tools = new FakeTools();

    @BeforeEach
    void setUp() {
        client = AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(claude.baseUrl()).maxRetries(0).build();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void investigatesWithToolsThenSubmits() {
        script(
                message("tool_use", """
                        {"type": "thinking", "thinking": "", "signature": "sig-1"},
                        {"type": "tool_use", "id": "toolu_1", "name": "get_file",
                         "input": {"repo": "octo/shop", "path": "src/main/java/shop/PriceCalculator.java", "ref": "abc123"}}""",
                        1000, 200, 0, 3000),
                message("tool_use", SUBMIT, 1500, 300, 3000, 0));

        DiagnosisResult result = agent(properties(15, 400_000)).diagnose(context());

        assertThat(result.source()).isEqualTo("AGENT");
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.diagnosis().confidence()).isEqualTo(Diagnosis.Confidence.HIGH);
        assertThat(result.diagnosis().evidence()).singleElement()
                .satisfies(e -> assertThat(e.path()).isEqualTo("src/main/java/shop/PriceCalculator.java"));
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("get_file");
            assertThat(call.isError()).isFalse();
        });
        assertThat(tools.calls).containsExactly("get_file");
        // 2500 input * $4 + 500 output * $20 + 3000 cache read * $0.20 + 3000 cache write * $5, per million
        assertThat(result.usage().costUsd()).isCloseTo(0.0356, org.assertj.core.data.Offset.offset(1e-9));

        claude.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withHeader("anthropic-beta", containing("server-side-fallback-2026-07-01"))
                .withRequestBody(matchingJsonPath("$.fallbacks", equalTo("default")))
                .withRequestBody(matchingJsonPath("$.model", equalTo("claude-opus-5-5")))
                .withRequestBody(matchingJsonPath("$.output_config.effort", equalTo("high")))
                .withRequestBody(matchingJsonPath("$.system[0].cache_control.type", equalTo("ephemeral")))
                .withRequestBody(matchingJsonPath("$.tools[?(@.name == 'get_file')]"))
                .withRequestBody(matchingJsonPath("$.tools[?(@.name == 'submit_diagnosis' && @.strict == true)]")));
        // Second request: the assistant turn goes back unchanged (thinking signature included),
        // followed by the tool result.
        claude.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.messages[1].content[0].signature", equalTo("sig-1")))
                .withRequestBody(matchingJsonPath("$.messages[2].content[0].tool_use_id", equalTo("toolu_1")))
                .withRequestBody(matchingJsonPath("$.messages[2].content[0].content", containing("return price"))));
    }

    @Test
    void remindsTheModelToSubmitWhenItStopsWithText() {
        script(message("end_turn", "{\"type\": \"text\", \"text\": \"It's the discount math.\"}", 100, 10, 0, 0),
                message("tool_use", SUBMIT, 100, 10, 0, 0));

        DiagnosisResult result = agent(properties(15, 400_000)).diagnose(context());

        assertThat(result.steps()).isEqualTo(2);
        claude.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.messages[2].content", containing("submit_diagnosis"))));
    }

    @Test
    void asksAgainWhenTheSubmissionIsInvalid() {
        script(message("tool_use", SUBMIT.replace("TEST_FAILURE", "TYPO"), 100, 10, 0, 0),
                message("tool_use", SUBMIT, 100, 10, 0, 0));

        DiagnosisResult result = agent(properties(15, 400_000)).diagnose(context());

        assertThat(result.diagnosis().category()).isEqualTo("TEST_FAILURE");
        claude.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.messages[2].content[0].is_error", equalTo("true"))));
    }

    @Test
    void tellsTheModelToWrapUpWhenTheBudgetIsReached() {
        script(message("tool_use", """
                        {"type": "tool_use", "id": "toolu_1", "name": "get_file", "input": {"path": "a"}}""",
                        5000, 100, 0, 0),
                message("tool_use", SUBMIT, 100, 10, 0, 0));

        agent(properties(15, 1000)).diagnose(context());

        claude.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.messages[2].content[1].text", containing("budget"))));
    }

    @Test
    void stopsAfterTheStepLimit() {
        claude.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(okJson(message("tool_use", """
                {"type": "tool_use", "id": "toolu_1", "name": "get_file", "input": {"path": "a"}}""", 10, 10, 0, 0))));

        assertThatThrownBy(() -> agent(properties(3, 400_000)).diagnose(context()))
                .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessageContaining("3 steps");
        assertThat(tools.calls).hasSize(3);
    }

    @Test
    void treatsARefusalAsPermanent() {
        claude.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(okJson(message("refusal", "", 10, 0, 0, 0))));

        assertThatThrownBy(() -> agent(properties(15, 400_000)).diagnose(context()))
                .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessageContaining("declined");
    }

    @Test
    void explainsMissingOrInvalidCredentials() {
        claude.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\": \"error\", \"error\": {\"type\": \"authentication_error\", \"message\": \"x-api-key header is required\"}}")));

        assertThatThrownBy(() -> agent(properties(15, 400_000)).diagnose(context()))
                .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void treatsRateLimitsAndOverloadAsRetryable() {
        claude.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(aResponse().withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\": \"error\", \"error\": {\"type\": \"rate_limit_error\", \"message\": \"slow down\"}}")));

        assertThatThrownBy(() -> agent(properties(15, 400_000)).diagnose(context()))
                .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void canTurnOffServerSideFallback() {
        script(message("tool_use", SUBMIT, 10, 10, 0, 0));
        DiagnosisProperties defaults = properties(15, 400_000);
        DiagnosisProperties noFallback = new DiagnosisProperties(true, defaults.model(), defaults.effort(), 15,
                defaults.maxTokensPerTurn(), 400_000, false, 3, defaults.mcp(), defaults.pricing());

        agent(noFallback).diagnose(context());

        claude.verify(postRequestedFor(urlPathEqualTo("/v1/messages")).withoutHeader("anthropic-beta"));
    }

    private DiagnosisAgent agent(DiagnosisProperties properties) {
        return new DiagnosisAgent(client, tools, properties, Clock.systemUTC());
    }

    /** Responses served in order, one per request. */
    private void script(String... responses) {
        String state = STARTED;
        for (int i = 0; i < responses.length; i++) {
            String next = "step-" + (i + 1);
            claude.stubFor(post(urlPathEqualTo("/v1/messages")).inScenario("agent").whenScenarioStateIs(state)
                    .willSetStateTo(next).willReturn(okJson(responses[i])));
            state = next;
        }
    }

    private static String message(String stopReason, String content, long input, long output, long cacheRead,
                                  long cacheWrite) {
        return """
                {"id": "msg_test", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "content": [%s], "stop_reason": "%s", "stop_sequence": null,
                 "usage": {"input_tokens": %d, "output_tokens": %d, "cache_read_input_tokens": %d,
                           "cache_creation_input_tokens": %d}}"""
                .formatted(content, stopReason, input, output, cacheRead, cacheWrite);
    }

    static DiagnosisProperties properties(int maxSteps, long budget) {
        return new DiagnosisProperties(true, "claude-opus-5-5", "high", maxSteps, 16000, budget, true, 3,
                new DiagnosisProperties.Mcp(java.net.URI.create("http://localhost/mcp"), null),
                new DiagnosisProperties.Pricing(4.00, 20.00, 0.20, 5.00));
    }

    static FailureContext context() {
        return new FailureContext(1, 2, "octo/shop", "CI", 9001, "main", "abc123", "push", 301, "test", "failure",
                List.of("Run tests"),
                new FailureContext.Analysis("TEST_FAILURE", "Run tests",
                        "PriceCalculatorTest.appliesPercentageDiscount: expected <90.0> but was <-900.0>",
                        "[ERROR] PriceCalculatorTest.appliesPercentageDiscount:14 expected: <90.0> but was: <-900.0>",
                        120, List.of(new FailureContext.CodeReference("src/test/java/shop/PriceCalculatorTest.java", 14)),
                        "fp"),
                List.of(), List.of());
    }

    static class FakeTools implements AgentTools {
        final List<String> calls = new ArrayList<>();

        @Override
        public List<Spec> list() {
            return List.of(new Spec("get_file", "Read a file at a commit", Map.of(
                    "type", "object",
                    "properties", Map.of("repo", Map.of("type", "string"), "path", Map.of("type", "string"),
                            "ref", Map.of("type", "string")),
                    "required", List.of("path"))));
        }

        @Override
        public Result call(String name, Map<String, Object> arguments) {
            calls.add(name);
            return new Result("7: return price * (1 - percent);", false);
        }
    }
}
