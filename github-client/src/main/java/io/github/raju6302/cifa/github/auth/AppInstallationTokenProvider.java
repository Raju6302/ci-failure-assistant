package io.github.raju6302.cifa.github.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.client.RestClient;

/**
 * Exchanges the App JWT for an installation access token and caches it until shortly
 * before it expires (GitHub issues them for one hour).
 */
public class AppInstallationTokenProvider implements GitHubTokenProvider {

    private static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofMinutes(5);

    private final long appId;
    private final PrivateKey privateKey;
    private final RestClient restClient;
    private final Clock clock;
    private final Map<Long, InstallationToken> cache = new ConcurrentHashMap<>();

    public AppInstallationTokenProvider(long appId, PrivateKey privateKey, RestClient restClient, Clock clock) {
        this.appId = appId;
        this.privateKey = privateKey;
        this.restClient = restClient;
        this.clock = clock;
    }

    @Override
    public String token(Long installationId) {
        if (installationId == null) {
            throw new MissingCredentialsException("GitHub App auth needs an installation id");
        }
        return cache.compute(installationId, (id, cached) ->
                cached != null && cached.validAt(clock.instant()) ? cached : requestToken(id)).token();
    }

    private InstallationToken requestToken(long installationId) {
        InstallationToken token = restClient.post()
                .uri("/app/installations/{id}/access_tokens", installationId)
                .header("Authorization", "Bearer " + AppJwt.create(appId, privateKey, clock.instant()))
                .retrieve()
                .body(InstallationToken.class);
        if (token == null || token.token() == null) {
            throw new IllegalStateException("GitHub returned no installation token");
        }
        return token;
    }

    record InstallationToken(String token, @JsonProperty("expires_at") Instant expiresAt) {

        boolean validAt(Instant now) {
            return expiresAt != null && now.isBefore(expiresAt.minus(REFRESH_BEFORE_EXPIRY));
        }
    }
}
