package io.github.raju6302.cifa.github.auth;

/**
 * Picks the right credential: the App's installation token when the request has an
 * installation id and an App is configured, otherwise the static access token.
 */
public class ConfiguredTokenProvider implements GitHubTokenProvider {

    private final GitHubTokenProvider app;
    private final String staticToken;

    /** Either argument may be null when that auth method is not configured. */
    public ConfiguredTokenProvider(GitHubTokenProvider app, String staticToken) {
        this.app = app;
        this.staticToken = staticToken;
    }

    @Override
    public String token(Long installationId) {
        if (app != null && installationId != null) {
            return app.token(installationId);
        }
        if (staticToken != null && !staticToken.isBlank()) {
            return staticToken;
        }
        throw new MissingCredentialsException(
                "No GitHub credentials configured: set GITHUB_APP_ID + GITHUB_APP_PRIVATE_KEY_PATH, or GITHUB_TOKEN");
    }
}
