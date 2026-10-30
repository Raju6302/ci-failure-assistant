package io.github.raju6302.cifa.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Talks to the running server the way the agent will: a real MCP client over Streamable HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cifa.github.token=test-token", "cifa.mcp.max-output-chars=4000"})
@Import(TestcontainersConfiguration.class)
class McpServerIntegrationTests {

    /** Generated per run so no token-like literal ends up in the repository. */
    static final String AUTH_TOKEN = UUID.randomUUID().toString();
    static final String SHA = "1234567890abcdef1234567890abcdef12345678";
    static final long JOB_ID = 301;

    @RegisterExtension
    static WireMockExtension github = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("cifa.github.api-url", github::baseUrl);
        registry.add("cifa.mcp.auth-token", () -> AUTH_TOKEN);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    McpSyncClient client;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE failed_job, workflow_run, repository RESTART IDENTITY CASCADE").update();
        long repoId = jdbc.sql("INSERT INTO repository (github_id, full_name) VALUES (1, 'octo/shop') RETURNING id")
                .query(Long.class).single();
        long runId = jdbc.sql("""
                        INSERT INTO workflow_run (repository_id, github_run_id, run_attempt, run_number, workflow_name,
                                                  event, head_branch, head_sha, conclusion, html_url, status)
                        VALUES (:repoId, 9001, 1, 12, 'CI', 'push', 'main', :sha, 'failure',
                                'https://github.com/octo/shop/actions/runs/9001', 'INGESTED')
                        RETURNING id""")
                .param("repoId", repoId)
                .param("sha", SHA)
                .query(Long.class).single();
        String log = IntStream.rangeClosed(1, 400)
                .mapToObj(i -> i == 250 ? "[ERROR] InvoiceServiceTest.rounds:42 expected 10.05 but was 10.04"
                        : "[INFO] step output line " + i)
                .collect(Collectors.joining("\n", "", "\n"));
        jdbc.sql("""
                        INSERT INTO failed_job (workflow_run_id, github_job_id, name, conclusion, failed_steps, log_text)
                        VALUES (:runId, :jobId, 'test', 'failure', ARRAY['Run tests'], :log)""")
                .param("runId", runId)
                .param("jobId", JOB_ID)
                .param("log", log)
                .update();
        client = connect(AUTH_TOKEN);
    }

    @AfterEach
    void tearDown() {
        client.closeGracefully();
    }

    @Test
    void listsTheToolsWithSnakeCaseArguments() {
        McpSchema.ListToolsResult tools = client.listTools();

        assertThat(client.getServerInfo().name()).isEqualTo("cifa-github");
        assertThat(tools.tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder(
                "get_commit_diff", "get_file", "list_recent_commits", "search_code", "get_workflow_file",
                "get_job_log_section");
        McpSchema.Tool getFile = tools.tools().stream().filter(tool -> tool.name().equals("get_file")).findFirst().orElseThrow();
        assertThat(getFile.inputSchema()).extractingByKey("required").asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("repo", "path", "ref");
        assertThat(getFile.annotations().readOnlyHint()).isTrue();
    }

    @Test
    void getFileReturnsNumberedLinesAndCachesLookupsBySha() {
        stubFile("src/Price.java", "package shop;\n\nclass Price {\n    long cents;\n}\n");

        CallToolResult result = call("get_file", Map.of("repo", "octo/shop", "path", "src/Price.java", "ref", SHA,
                "start_line", 3, "end_line", 4));
        call("get_file", Map.of("repo", "OCTO/shop", "path", "src/Price.java", "ref", SHA));

        assertThat(result.isError()).isFalse();
        assertThat(text(result))
                .contains("src/Price.java at %s in octo/shop (lines 3-4 of 5)".formatted(SHA))
                .contains("3 | class Price {")
                .contains("4 |     long cents;")
                .doesNotContain("package shop");
        github.verify(1, getRequestedFor(urlPathEqualTo("/repos/octo/shop/contents/src/Price.java")));
    }

    @Test
    void getFileSaysHowToReadOnWhenTheOutputLimitIsReached() {
        String big = IntStream.rangeClosed(1, 500).mapToObj(i -> "line number " + i).collect(Collectors.joining("\n"));
        stubFile("Big.java", big);

        String text = text(call("get_file", Map.of("repo", "octo/shop", "path", "Big.java", "ref", SHA)));

        assertThat(text.length()).isLessThanOrEqualTo(4000);
        assertThat(text).contains("[Truncated: limited to 4000 characters.")
                .containsPattern("Call get_file again with start_line=\\d+ to read on");
    }

    @Test
    void deniedPathsAreRefusedWithoutAskingGitHub() {
        CallToolResult result = call("get_file", Map.of("repo", "octo/shop", "path", "./Evals//sandbox-expected.json",
                "ref", SHA));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("not allowed by this server's policy");
        github.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void unknownRepositoriesAreRefused() {
        CallToolResult result = call("get_file", Map.of("repo", "someone/else", "path", "README.md", "ref", "main"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("someone/else is not available");
        github.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void getCommitDiffShowsPatchesAndDropsDeniedFiles() {
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/commits/" + SHA)).willReturn(okJson("""
                {"sha": "%s",
                 "commit": {"message": "Round invoices half-even", "author": {"name": "Jane Dev", "date": "2026-10-30T10:00:00Z"}},
                 "parents": [{"sha": "parent1"}],
                 "files": [
                   {"filename": "src/InvoiceService.java", "status": "modified", "additions": 1, "deletions": 1,
                    "patch": "@@ -40,3 +40,3 @@\\n-    return round(HALF_UP);\\n+    return round(HALF_EVEN);"},
                   {"filename": "evals/sandbox-expected.json", "status": "modified", "additions": 1, "deletions": 0,
                    "patch": "+  \\"rootCause\\": \\"the answer\\""}
                 ]}""".formatted(SHA))));

        CallToolResult result = call("get_commit_diff", Map.of("repo", "octo/shop", "sha", SHA));

        assertThat(result.isError()).isFalse();
        assertThat(text(result))
                .contains("Round invoices half-even", "Jane Dev", "--- src/InvoiceService.java (modified, +1 -1)",
                        "+    return round(HALF_EVEN);")
                .contains("2 file(s) changed, 1 not shown because of this server's path policy")
                .doesNotContain("sandbox-expected", "the answer");
    }

    @Test
    void getJobLogSectionGrepsTheStoredLogNotGitHub() {
        CallToolResult result = call("get_job_log_section", Map.of("github_job_id", JOB_ID, "grep", "error",
                "context_lines", 1));

        assertThat(result.isError()).isFalse();
        assertThat(text(result))
                .contains("Log of job 301 \"test\" (failure; failed steps: Run tests), run 9001 in octo/shop: 400 lines.")
                .contains("1 line(s) match /error/")
                .contains("249 | [INFO] step output line 249")
                .contains("250 > [ERROR] InvoiceServiceTest.rounds:42")
                .contains("251 | [INFO] step output line 251")
                .doesNotContain("line 248", "line 252");
        github.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void getJobLogSectionReadsRangesAndDefaultsToTheEnd() {
        String range = text(call("get_job_log_section", Map.of("github_job_id", JOB_ID, "start_line", 10, "end_line", 11)));
        String tail = text(call("get_job_log_section", Map.of("github_job_id", JOB_ID)));

        assertThat(range).contains(" 10 | [INFO] step output line 10\n 11 | [INFO] step output line 11\n")
                .doesNotContain("line 12");
        assertThat(tail).contains("the end of the log").endsWith("400 | [INFO] step output line 400\n")
                .doesNotContain("line 1\n");
        assertThat(text(call("get_job_log_section", Map.of("github_job_id", 999)))).contains("No failed job");
    }

    @Test
    void getWorkflowFileReadsTheFileAtTheRunsCommit() {
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/actions/runs/9001")).willReturn(okJson("""
                {"id": 9001, "name": "CI", "path": ".github/workflows/ci.yml", "head_sha": "%s",
                 "head_branch": "main", "run_attempt": 1}""".formatted(SHA))));
        stubFile(".github/workflows/ci.yml", "name: CI\non: push\njobs:\n  test:\n    runs-on: ubuntu-latest\n");

        String text = text(call("get_workflow_file", Map.of("repo", "octo/shop", "run_id", 9001)));

        assertThat(text).contains("Workflow \"CI\" of run 9001", ".github/workflows/ci.yml at " + SHA, "5 |     runs-on: ubuntu-latest");
    }

    @Test
    void listsRecentCommitsAndSearchesCode() {
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/commits")).withQueryParam("sha", equalTo("main"))
                .willReturn(okJson("""
                        [{"sha": "%s", "commit": {"message": "Round invoices\\n\\ndetails", "author": {"name": "Jane Dev", "date": "2026-10-30T10:00:00Z"}}}]"""
                        .formatted(SHA))));
        github.stubFor(get(urlPathEqualTo("/search/code")).willReturn(okJson("""
                {"total_count": 2, "incomplete_results": false, "items": [
                  {"path": "src/InvoiceService.java", "sha": "s1", "repository": {"full_name": "octo/shop"},
                   "text_matches": [{"fragment": "class InvoiceService {"}]},
                  {"path": "evals/expected.json", "sha": "s2", "repository": {"full_name": "octo/shop"},
                   "text_matches": [{"fragment": "InvoiceService answer"}]}]}""")));

        String commits = text(call("list_recent_commits", Map.of("repo", "octo/shop", "branch", "main", "limit", 5)));
        String search = text(call("search_code", Map.of("repo", "octo/shop", "query", "InvoiceService")));

        assertThat(commits).contains(SHA + "  2026-10-30T10:00:00Z  Jane Dev: Round invoices").doesNotContain("details");
        assertThat(search).contains("src/InvoiceService.java", "    class InvoiceService {",
                "1 result(s) not shown because of this server's path policy").doesNotContain("answer");
        assertThat(text(call("search_code", Map.of("repo", "octo/shop", "query", "secret repo:octo/private"))))
                .contains("Don't use repo:");
    }

    @Test
    void gitHubErrorsBecomeToolErrorsWithoutDetails() {
        github.stubFor(get(urlPathMatching("/repos/octo/shop/contents/.*")).willReturn(aResponse().withStatus(404)
                .withBody("{\"message\": \"Not Found\"}")));
        github.stubFor(get(urlPathMatching("/repos/octo/shop/commits/.*")).willReturn(aResponse().withStatus(503)
                .withBody("upstream detail that must not leak")));

        CallToolResult missing = call("get_file", Map.of("repo", "octo/shop", "path", "nope.txt", "ref", "main"));
        CallToolResult unavailable = call("get_commit_diff", Map.of("repo", "octo/shop", "sha", "main"));

        assertThat(missing.isError()).isTrue();
        assertThat(text(missing)).startsWith("Not found on GitHub");
        assertThat(unavailable.isError()).isTrue();
        assertThat(text(unavailable)).contains("temporarily unavailable", "503").doesNotContain("upstream detail");
    }

    @Test
    void requestsWithoutTheRightBearerTokenAreRejected() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String initialize = """
                {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18",
                 "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}}}""";

        assertThat(post(http, initialize, null).statusCode()).isEqualTo(401);
        assertThat(post(http, initialize, "Bearer wrong-" + AUTH_TOKEN).statusCode()).isEqualTo(401);
        assertThat(post(http, initialize, "Bearer " + AUTH_TOKEN).statusCode()).isEqualTo(200);
        assertThat(http.send(HttpRequest.newBuilder(URI.create("http://localhost:%d/actuator/health".formatted(port))).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> {
            try (McpSyncClient intruder = McpClient.sync(transport("not-the-token")).build()) {
                intruder.initialize();
            }
        }).isInstanceOf(RuntimeException.class);
    }

    private HttpResponse<String> post(HttpClient http, String body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:%d/mcp".formatted(port)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private McpSyncClient connect(String token) {
        McpSyncClient mcp = McpClient.sync(transport(token)).requestTimeout(Duration.ofSeconds(20)).build();
        mcp.initialize();
        return mcp;
    }

    private HttpClientStreamableHttpTransport transport(String token) {
        return HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
                .httpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("Authorization", "Bearer " + token))
                .build();
    }

    private CallToolResult call(String tool, Map<String, Object> arguments) {
        return client.callTool(new CallToolRequest(tool, arguments));
    }

    private static String text(CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private static void stubFile(String path, String content) {
        String base64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/contents/" + path)).willReturn(okJson("""
                {"type": "file", "path": "%s", "sha": "blob1", "size": %d, "encoding": "base64", "content": "%s"}"""
                .formatted(path, content.length(), base64))));
    }
}
