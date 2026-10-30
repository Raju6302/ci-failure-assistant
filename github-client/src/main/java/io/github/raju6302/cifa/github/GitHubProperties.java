package io.github.raju6302.cifa.github;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

/**
 * GitHub connection settings ({@code cifa.github.*}).
 *
 * <p>Two ways to authenticate: a GitHub App (production; one token per installation) or a
 * single access token (local development). If both are set, the App is used for webhook
 * deliveries that carry an installation id, and the token for everything else.
 */
@ConfigurationProperties("cifa.github")
public record GitHubProperties(
        @DefaultValue("https://api.github.com") URI apiUrl,
        String token,
        @DefaultValue App app,
        /* Logs bigger than this keep only their last part, where errors usually are. */
        @DefaultValue("2MB") DataSize maxLogSize) {

    public record App(Long id, Path privateKeyPath, String privateKey) {

        public boolean isConfigured() {
            return id != null && (hasPath() || StringUtils.hasText(privateKey));
        }

        public boolean hasPath() {
            return privateKeyPath != null && !privateKeyPath.toString().isBlank();
        }
    }

    public boolean hasToken() {
        return StringUtils.hasText(token);
    }
}
