package io.github.raju6302.cifa.worker.redaction;

import static io.github.raju6302.cifa.worker.redaction.SecretRedactor.MASK;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Fake secrets are assembled at runtime so secret scanners don't flag this file. */
class SecretRedactorTest {

    static final String GITHUB_TOKEN = "ghp" + "_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";
    static final String AWS_KEY_ID = "AKIA" + "IOSFODNN7EXAMPL3";

    SecretRedactor redactor = new SecretRedactor();

    @Test
    void removesKnownTokenFormats() {
        String log = """
                Using token %s for checkout
                export AWS_ACCESS_KEY_ID=%s
                Slack webhook token %s
                """.formatted(GITHUB_TOKEN, AWS_KEY_ID, "xox" + "b-1234567890-abcdefghij");

        String redacted = redactor.redact(log);

        assertThat(redacted)
                .doesNotContain(GITHUB_TOKEN)
                .doesNotContain(AWS_KEY_ID)
                .doesNotContain("abcdefghij")
                .contains("Using token " + MASK + " for checkout");
    }

    @Test
    void removesCredentialsInUrlsAndHeaders() {
        String log = """
                Connecting to mongodb+srv://admin:Sup3rS3cret@cluster0.example.net/app
                curl -H "Authorization: %s abc.def.ghi-jkl" https://api.example.com
                """.formatted("Bearer");

        String redacted = redactor.redact(log);

        assertThat(redacted)
                .contains("mongodb+srv://" + MASK + "@cluster0.example.net/app")
                .contains("Authorization: Bearer " + MASK)
                .doesNotContain("Sup3rS3cret")
                .doesNotContain("abc.def.ghi-jkl");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DB_PASSWORD=hunter2hunter2", "api_key: \"k-123456789\"", "JWT_SECRET='Dating@Web79'",
            "spring.datasource.password=letmein123"})
    void removesSecretLookingAssignments(String line) {
        String redacted = redactor.redact(line);

        assertThat(redacted).contains(MASK);
        assertThat(redacted.substring(redacted.indexOf(MASK))).doesNotContainAnyWhitespaces();
        assertThat(redacted).doesNotContain("hunter2", "123456789", "Dating@Web79", "letmein123");
    }

    @Test
    void removesPrivateKeyBlocks() {
        String begin = "-----BEGIN RSA " + "PRIVATE KEY-----";
        String end = "-----END RSA " + "PRIVATE KEY-----";
        String log = "key:\n" + begin + "\nMIIEowIBAAKCAQEAxyz\nabc123\n" + end + "\ndone";

        assertThat(redactor.redact(log)).isEqualTo("key:\n" + MASK + "\ndone");
    }

    @Test
    void leavesOrdinaryBuildOutputAlone() {
        String log = """
                [INFO] Tests run: 12, Failures: 1, Errors: 0, Skipped: 0
                [ERROR] PriceCalculatorTest.appliesDiscount:14 expected: <90.0> but was: <0.9>
                token: ***
                Downloading https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.11.0/junit-bom-5.11.0.pom
                """;

        assertThat(redactor.redact(log)).isEqualTo(log);
    }
}
