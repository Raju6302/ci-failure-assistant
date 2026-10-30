package io.github.raju6302.cifa.worker.diagnosis;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent tools served by mcp-github-server over MCP (Streamable HTTP). Connects lazily and
 * reconnects after a failure, so the worker starts even when the MCP server isn't up yet.
 */
class McpAgentTools implements AgentTools, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpAgentTools.class);

    private final DiagnosisProperties.Mcp config;
    private McpSyncClient client;

    McpAgentTools(DiagnosisProperties.Mcp config) {
        this.config = config;
    }

    @Override
    public List<Spec> list() {
        try {
            List<Spec> specs = new ArrayList<>();
            String cursor = null;
            do {
                McpSchema.ListToolsResult page = client().listTools(cursor);
                page.tools().forEach(tool -> specs.add(new Spec(tool.name(), tool.description(), tool.inputSchema())));
                cursor = page.nextCursor();
            } while (cursor != null);
            return specs;
        } catch (RuntimeException e) {
            reset();
            throw new AgentException("MCP server unavailable at " + config.url() + ": " + e.getMessage(), true, e);
        }
    }

    @Override
    public Result call(String name, Map<String, Object> arguments) {
        McpSchema.CallToolResult result;
        try {
            result = client().callTool(new McpSchema.CallToolRequest(name, arguments));
        } catch (RuntimeException e) {
            reset();
            return new Result("The tool server could not be reached: " + e.getMessage(), true);
        }
        String text = result.content().stream()
                .map(content -> content instanceof McpSchema.TextContent textContent ? textContent.text() : "[non-text content]")
                .collect(Collectors.joining("\n"));
        return new Result(text, Boolean.TRUE.equals(result.isError()));
    }

    private synchronized McpSyncClient client() {
        if (client == null) {
            URI url = config.url();
            String token = config.authToken();
            HttpClientStreamableHttpTransport.Builder transport = HttpClientStreamableHttpTransport
                    .builder(url.getScheme() + "://" + url.getAuthority())
                    .endpoint(url.getPath())
                    .connectTimeout(Duration.ofSeconds(10));
            if (token != null && !token.isBlank()) {
                transport.httpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("Authorization", "Bearer " + token));
            }
            McpSyncClient connected = McpClient.sync(transport.build())
                    .requestTimeout(Duration.ofSeconds(60))
                    .clientInfo(new McpSchema.Implementation("cifa-analysis-worker", "0.1.0"))
                    .build();
            connected.initialize();
            client = connected;
            log.info("Connected to MCP server at {}", url);
        }
        return client;
    }

    private synchronized void reset() {
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // already broken; nothing to clean up
            }
            client = null;
        }
    }

    @Override
    public void close() {
        reset();
    }
}
