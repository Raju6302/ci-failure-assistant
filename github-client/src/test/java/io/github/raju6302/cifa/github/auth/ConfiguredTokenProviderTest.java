package io.github.raju6302.cifa.github.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConfiguredTokenProviderTest {

    GitHubTokenProvider app = id -> "app-token-" + id;

    @Test
    void usesAppTokenWhenTheEventHasAnInstallation() {
        assertThat(new ConfiguredTokenProvider(app, "static").token(9L)).isEqualTo("app-token-9");
    }

    @Test
    void fallsBackToStaticTokenWithoutInstallation() {
        assertThat(new ConfiguredTokenProvider(app, "static").token(null)).isEqualTo("static");
        assertThat(new ConfiguredTokenProvider(null, "static").token(9L)).isEqualTo("static");
    }

    @Test
    void failsClearlyWhenNothingIsConfigured() {
        assertThatThrownBy(() -> new ConfiguredTokenProvider(null, " ").token(null))
                .isInstanceOf(MissingCredentialsException.class)
                .hasMessageContaining("GITHUB_TOKEN");
    }
}
