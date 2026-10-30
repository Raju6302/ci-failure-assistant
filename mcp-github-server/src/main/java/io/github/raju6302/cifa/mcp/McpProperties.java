package io.github.raju6302.cifa.mcp;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

/**
 * {@code cifa.mcp.*}
 *
 * @param authToken      shared bearer token every MCP request must carry; when empty the
 *                       server is open to anyone who can reach it
 * @param deniedPaths    glob patterns (Ant style, case-insensitive) of repository paths no
 *                       tool may return; the eval ground truth must never reach the agent
 * @param maxOutputChars upper bound on any single tool result, so one call can't flood the
 *                       model's context
 * @param maxPatchChars  upper bound on each file's patch in a commit diff
 * @param cache          cache for lookups that can't change: commits and files by full sha
 */
@ConfigurationProperties("cifa.mcp")
public record McpProperties(
        String authToken,
        @DefaultValue("evals/**") List<String> deniedPaths,
        @DefaultValue("20000") int maxOutputChars,
        @DefaultValue("4000") int maxPatchChars,
        @DefaultValue Cache cache) {

    /**
     * @param ttl     how long an entry is kept after it was fetched
     * @param maxSize memory budget of the file cache and of the commit cache, each
     */
    public record Cache(@DefaultValue("PT30M") Duration ttl, @DefaultValue("32MB") DataSize maxSize) {
    }

    public McpProperties {
        if (maxOutputChars < 2_000 || maxPatchChars < 1_000) {
            throw new IllegalArgumentException("cifa.mcp.max-output-chars must be at least 2000 and "
                    + "cifa.mcp.max-patch-chars at least 1000, or tools can't show anything useful");
        }
    }

    public boolean hasAuthToken() {
        return StringUtils.hasText(authToken);
    }

    /** Keeps the token out of logs and error messages that print this record. */
    @Override
    public String toString() {
        return "McpProperties[authToken=%s, deniedPaths=%s, maxOutputChars=%d, maxPatchChars=%d, cache=%s]"
                .formatted(hasAuthToken() ? "(set)" : "(not set)", deniedPaths, maxOutputChars, maxPatchChars, cache);
    }
}
