package io.github.raju6302.cifa.github;

/** A GitHub API call failed. {@link #isRetryable()} tells callers whether trying again can help. */
public class GitHubApiException extends RuntimeException {

    private final int status;

    public GitHubApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** Server errors and rate limits are temporary; 4xx client errors are not. */
    public boolean isRetryable() {
        return status >= 500 || status == 429 || status == 403 && getMessage().contains("rate limit");
    }

    public boolean isNotFound() {
        return status == 404 || status == 410;
    }
}
