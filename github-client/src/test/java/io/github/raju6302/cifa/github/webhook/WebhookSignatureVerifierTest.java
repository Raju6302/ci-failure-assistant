package io.github.raju6302.cifa.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    // Example from GitHub's docs: https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries
    static final String SECRET = "It's a Secret to Everybody";
    static final byte[] BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    static final String SIGNATURE = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

    WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);

    @Test
    void acceptsGitHubsDocumentedExample() {
        assertThat(verifier.isValid(BODY, SIGNATURE)).isTrue();
        assertThat(verifier.signatureFor(BODY)).isEqualTo(SIGNATURE);
    }

    @Test
    void rejectsTamperedBodyOrBadHeaders() {
        assertThat(verifier.isValid("Hello, World?".getBytes(StandardCharsets.UTF_8), SIGNATURE)).isFalse();
        assertThat(verifier.isValid(BODY, null)).isFalse();
        assertThat(verifier.isValid(BODY, SIGNATURE.substring(7))).isFalse();
        assertThat(verifier.isValid(BODY, "sha256=not-hex")).isFalse();
        assertThat(new WebhookSignatureVerifier("another secret").isValid(BODY, SIGNATURE)).isFalse();
    }

    @Test
    void refusesEmptySecret() {
        assertThatThrownBy(() -> new WebhookSignatureVerifier(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
