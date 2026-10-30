package io.github.raju6302.cifa.github.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

class AppInstallationTokenProviderTest {

    @RegisterExtension
    static WireMockExtension github = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static KeyPair keys;
    static String pkcs1Pem;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(keys); // writes the PKCS#1 format GitHub hands out
        }
        pkcs1Pem = out.toString();
    }

    @Test
    void requestsTokenWithSignedJwtAndCachesIt() throws Exception {
        Instant now = Instant.parse("2026-10-30T10:00:00Z");
        stubToken(42, "installation-token-1", now.plus(Duration.ofHours(1)));
        AppInstallationTokenProvider provider = provider(Clock.fixed(now, ZoneId.of("UTC")));

        assertThat(provider.token(42L)).isEqualTo("installation-token-1");
        assertThat(provider.token(42L)).isEqualTo("installation-token-1");

        github.verify(1, postRequestedFor(urlEqualTo("/app/installations/42/access_tokens")));
        String jwt = github.getAllServeEvents().getFirst().getRequest().getHeader("Authorization").substring(7);
        assertValidJwt(jwt, now);
    }

    @Test
    void refreshesTokenShortlyBeforeItExpires() {
        Instant now = Instant.parse("2026-10-30T10:00:00Z");
        stubToken(7, "short-lived", now.plus(Duration.ofMinutes(3)));
        AppInstallationTokenProvider provider = provider(Clock.fixed(now, ZoneId.of("UTC")));

        provider.token(7L);
        provider.token(7L);

        github.verify(2, postRequestedFor(urlEqualTo("/app/installations/7/access_tokens")));
    }

    @Test
    void needsAnInstallationId() {
        assertThatThrownBy(() -> provider(Clock.systemUTC()).token(null))
                .isInstanceOf(MissingCredentialsException.class);
    }

    private AppInstallationTokenProvider provider(Clock clock) {
        RestClient restClient = RestClient.builder().baseUrl(github.baseUrl()).build();
        return new AppInstallationTokenProvider(1234, PrivateKeys.fromPem(pkcs1Pem), restClient, clock);
    }

    private static void stubToken(long installationId, String token, Instant expiresAt) {
        github.stubFor(post(urlEqualTo("/app/installations/%d/access_tokens".formatted(installationId)))
                .willReturn(okJson("{\"token\": \"%s\", \"expires_at\": \"%s\"}".formatted(token, expiresAt))));
    }

    private static void assertValidJwt(String jwt, Instant now) throws Exception {
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();

        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payload)
                .contains("\"iss\":\"1234\"")
                .contains("\"iat\":" + now.minusSeconds(60).getEpochSecond())
                .contains("\"exp\":" + now.plusSeconds(540).getEpochSecond());
    }
}
