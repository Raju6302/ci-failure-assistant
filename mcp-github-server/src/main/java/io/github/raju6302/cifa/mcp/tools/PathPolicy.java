package io.github.raju6302.cifa.mcp.tools;

import io.github.raju6302.cifa.mcp.McpProperties;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * Repository paths no tool may return ({@code cifa.mcp.denied-paths}). By default that's
 * {@code evals/**}: the expected diagnoses the agent is scored against must never reach it.
 *
 * <p>Matching is case-insensitive and runs on a normalized path, so {@code ./Evals//x}
 * can't slip past a pattern for {@code evals/**}.
 */
@Component
class PathPolicy {

    private final List<String> deniedPatterns;
    private final AntPathMatcher matcher = new AntPathMatcher();

    PathPolicy(McpProperties properties) {
        this.deniedPatterns = properties.deniedPaths().stream().map(PathPolicy::normalize).toList();
        this.matcher.setCaseSensitive(false);
    }

    boolean isDenied(String path) {
        String normalized = normalize(path);
        return deniedPatterns.stream().anyMatch(pattern -> matcher.match(pattern, normalized));
    }

    /** @throws ToolException if the path is denied or isn't a plain relative path */
    String requireAllowed(String path) {
        String normalized = normalize(path);
        if (normalized.isEmpty()) {
            throw new ToolException("A file path is required, relative to the repository root.");
        }
        if (Arrays.asList(normalized.split("/")).contains("..")) {
            throw new ToolException("Paths must be relative to the repository root, without '..': " + path);
        }
        if (isDenied(normalized)) {
            throw new ToolException("Access to %s is not allowed by this server's policy.".formatted(path));
        }
        return normalized;
    }

    /** Drops empty and "." segments, so "/./src//App.java" becomes "src/App.java". */
    static String normalize(String path) {
        return Arrays.stream(path.strip().replace('\\', '/').split("/"))
                .filter(segment -> !segment.isEmpty() && !segment.equals("."))
                .collect(Collectors.joining("/"));
    }
}
