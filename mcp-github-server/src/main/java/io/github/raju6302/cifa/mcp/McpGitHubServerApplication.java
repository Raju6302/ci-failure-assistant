package io.github.raju6302.cifa.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP server that will expose GitHub and CI tools (diffs, files, commits, workflow
 * files, job logs) to the diagnosis agent. Tools arrive in Phase 3.
 */
@SpringBootApplication
public class McpGitHubServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpGitHubServerApplication.class, args);
    }
}
