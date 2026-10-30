package io.github.raju6302.cifa.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * MCP server that gives the diagnosis agent read-only tools: commit diffs, files, recent
 * commits, code search, workflow files and the stored (redacted) job logs.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class McpGitHubServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpGitHubServerApplication.class, args);
    }
}
