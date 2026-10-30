package io.github.raju6302.cifa.mcp.tools;

import io.github.raju6302.cifa.github.GitHubApiException;
import io.github.raju6302.cifa.github.auth.MissingCredentialsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a failed tool call into a message for the agent: what went wrong and whether
 * retrying can help. Raw exception messages are never passed on (a GitHub error body or a
 * stack trace has nothing the agent needs), except for this project's own validation messages.
 */
final class ToolErrors {

    private static final Logger log = LoggerFactory.getLogger(ToolErrors.class);

    private ToolErrors() {
    }

    static String describe(String tool, RuntimeException error) {
        return switch (error) {
            case ToolException e -> e.getMessage();
            case GitHubApiException e when e.isNotFound() ->
                    "Not found on GitHub. Check the repository, ref (branch, tag or commit sha), path or id you passed.";
            case GitHubApiException e when e.isRetryable() -> {
                log.warn("{}: temporary GitHub error {}", tool, e.status());
                yield "GitHub is temporarily unavailable or rate-limiting this server (HTTP %d). This is temporary: "
                        .formatted(e.status()) + "continue with the information you already have, or try again later.";
            }
            case GitHubApiException e when e.status() == 422 ->
                    "GitHub could not process this request (HTTP 422). Check the arguments, such as the ref or the "
                            + "search query syntax.";
            case GitHubApiException e -> {
                log.warn("{}: GitHub refused the request: {}", tool, e.getMessage());
                yield "GitHub refused the request (HTTP %d); this server may not have access to it.".formatted(e.status());
            }
            case MissingCredentialsException e -> {
                log.error("{}: {}", tool, e.getMessage());
                yield "This server has no GitHub credentials configured, so it can't read repositories.";
            }
            // Thrown by this project's own argument checks (GitHubClient, LogGrep), with messages meant for callers.
            case IllegalArgumentException e -> e.getMessage();
            default -> {
                log.error("{} failed", tool, error);
                yield "Internal error in %s; details are in the server log.".formatted(tool);
            }
        };
    }
}
