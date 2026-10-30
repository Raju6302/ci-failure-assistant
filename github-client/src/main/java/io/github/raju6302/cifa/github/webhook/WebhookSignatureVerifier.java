package io.github.raju6302.cifa.github.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Checks the {@code X-Hub-Signature-256} header GitHub sends with every webhook:
 * an HMAC-SHA256 of the raw body, keyed with the shared webhook secret.
 */
public class WebhookSignatureVerifier {

    private static final String PREFIX = "sha256=";

    private final SecretKeySpec key;

    public WebhookSignatureVerifier(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Webhook secret must not be empty");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public boolean isValid(byte[] body, String signatureHeader) {
        if (body == null || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = sign(body);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()));
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        // Constant-time comparison so the signature can't be guessed byte by byte.
        return MessageDigest.isEqual(expected, actual);
    }

    public String signatureFor(byte[] body) {
        return PREFIX + HexFormat.of().formatHex(sign(body));
    }

    private byte[] sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
