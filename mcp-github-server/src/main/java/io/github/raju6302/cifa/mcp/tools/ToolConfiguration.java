package io.github.raju6302.cifa.mcp.tools;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers every {@link AgentTool} with the MCP server. The Spring AI starter picks up
 * {@code List<SyncToolSpecification>} beans. Tools are declared with explicit JSON schemas
 * rather than {@code @McpTool} methods so their argument names can be snake_case.
 */
@Configuration(proxyBeanMethods = false)
class ToolConfiguration {

    @Bean
    List<SyncToolSpecification> agentToolSpecifications(List<AgentTool> tools) {
        return tools.stream()
                .map(tool -> SyncToolSpecification.builder()
                        .tool(tool.definition())
                        .callHandler((exchange, request) -> invoke(tool, request))
                        .build())
                .toList();
    }

    /** Failures become error results (isError) the agent can read and react to, never protocol errors. */
    static CallToolResult invoke(AgentTool tool, CallToolRequest request) {
        String name = tool.definition().name();
        try {
            String text = tool.call(new ToolArguments(request.arguments()));
            return CallToolResult.builder().addTextContent(text).isError(false).build();
        } catch (RuntimeException e) {
            return CallToolResult.builder().addTextContent(ToolErrors.describe(name, e)).isError(true).build();
        }
    }
}
