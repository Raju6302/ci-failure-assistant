package io.github.raju6302.cifa.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;

/**
 * One MCP tool for the diagnosis agent. Implementations only return text or throw;
 * {@link ToolConfiguration} turns both into MCP results, so error handling lives in one place.
 */
interface AgentTool {

    McpSchema.Tool definition();

    /**
     * @return the text the agent sees
     * @throws ToolException for problems the agent should be told about
     */
    String call(ToolArguments arguments);

    /** Every tool here only reads, and calling it twice gives the same answer. */
    static McpSchema.Tool readOnly(String name, String title, String description, Map<String, Object> inputSchema) {
        return McpSchema.Tool.builder()
                .name(name)
                .title(title)
                .description(description)
                .inputSchema(inputSchema)
                .annotations(McpSchema.ToolAnnotations.builder()
                        .title(title)
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
    }
}
