package io.github.raju6302.cifa.github.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

/**
 * Builds the short-lived RS256 JWT a GitHub App uses to request installation tokens.
 * See https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app
 */
final class AppJwt {

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private AppJwt() {
    }

    static String create(long appId, PrivateKey key, Instant now) {
        // Backdate 60s to allow for clock drift; GitHub caps the lifetime at 10 minutes.
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iat\":%d,\"exp\":%d,\"iss\":\"%d\"}"
                .formatted(now.minusSeconds(60).getEpochSecond(), now.plusSeconds(540).getEpochSecond(), appId);
        String signingInput = encode(header) + "." + encode(payload);
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + BASE64URL.encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign GitHub App JWT", e);
        }
    }

    private static String encode(String json) {
        return BASE64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
