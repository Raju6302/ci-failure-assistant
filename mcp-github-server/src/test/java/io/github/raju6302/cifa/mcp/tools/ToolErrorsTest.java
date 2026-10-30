package io.github.raju6302.cifa.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.raju6302.cifa.github.GitHubApiException;
import io.github.raju6302.cifa.github.auth.MissingCredentialsException;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolErrorsTest {

    @Test
    void tellsTheAgentWhetherRetryingCanHelp() {
        assertThat(ToolErrors.describe("t", new GitHubApiException(404, "GitHub API returned 404: Not Found")))
                .startsWith("Not found on GitHub");
        assertThat(ToolErrors.describe("t", new GitHubApiException(403, "API rate limit exceeded for installation")))
                .contains("temporarily", "403");
        assertThat(ToolErrors.describe("t", new GitHubApiException(502, "Bad Gateway"))).contains("temporarily");
        assertThat(ToolErrors.describe("t", new GitHubApiException(422, "Validation Failed"))).contains("422");
    }

    @Test
    void neverPassesOnInternalDetails() {
        assertThat(ToolErrors.describe("t", new GitHubApiException(401, "Bad credentials for token abc")))
                .doesNotContain("abc", "credentials for");
        assertThat(ToolErrors.describe("t", new MissingCredentialsException("set GITHUB_APP_ID")))
                .doesNotContain("GITHUB_APP_ID");
        assertThat(ToolErrors.describe("get_file", new IllegalStateException("jdbc:postgresql://db/secret")))
                .isEqualTo("Internal error in get_file; details are in the server log.");
    }

    @Test
    void failuresBecomeErrorResultsNotExceptions() {
        AgentTool failing = new AgentTool() {
            @Override
            public McpSchema.Tool definition() {
                return AgentTool.readOnly("failing", "Failing", "Always fails", InputSchema.create().build());
            }

            @Override
            public String call(ToolArguments arguments) {
                throw new ToolException("Repository x/y is not available.");
            }
        };

        McpSchema.CallToolResult result = ToolConfiguration.invoke(failing, new McpSchema.CallToolRequest("failing", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).isEqualTo("Repository x/y is not available.");
    }
}
