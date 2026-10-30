package io.github.raju6302.cifa.github.auth;

/** Supplies the bearer token for a GitHub API call. */
@FunctionalInterface
public interface GitHubTokenProvider {

    /**
     * @param installationId GitHub App installation that owns the repo, or {@code null}
     *                       when the event did not come through an App
     */
    String token(Long installationId);
}
