package io.github.raju6302.cifa.worker.redaction;

import static io.github.raju6302.cifa.worker.redaction.SecretRedactor.MASK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.raju6302.cifa.worker.redaction.SecretRedactor.Redaction;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

        String redacted = redactor.redact(log).text();

        assertThat(redacted)
                .doesNotContain(GITHUB_TOKEN)
                .doesNotContain(AWS_KEY_ID)
                .doesNotContain("abcdefghij")
                .contains("Using token " + MASK + " for checkout");
    }

    /** One fake token per format, each printed the way a tool might print it. */
    static Stream<Arguments> tokenCorpus() {
        return Stream.of(
                Arguments.of("npm", "npm" + "_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"),
                Arguments.of("PyPI", "pypi" + "-" + "AgEIcHlwaS5vcmc" + "CJGI4ZDk5ZTA0LWE3YzAtNDU1My1iNjJlLTBmZGFkYWE4ODM5NQ"),
                Arguments.of("Google API key", "AIza" + "SyD-9tSrke72PouQMnMX-a7eZSW0jkFMBWY"),
                Arguments.of("Stripe live key", "sk" + "_live_" + "4eC39HqLyjWDarjtT1zdp7dc"),
                Arguments.of("Stripe restricted key", "rk" + "_test_" + "51H8sdfKJHsd8723hjsdfJSD"),
                Arguments.of("GitLab token", "glpat" + "-" + "xxXXyyYYzz00112233AA"),
                Arguments.of("Docker Hub token", "dckr" + "_pat_" + "2b3e36beeb386dfdaacb9b46004"),
                Arguments.of("Anthropic key", "sk" + "-ant-" + "api03-AbCdEfGhIjKlMnOpQrStUvWx"),
                Arguments.of("SendGrid key", "SG" + "." + "ngeVfQFYQlKU0ufo8x5d1A" + "." + "TwL2iGABf9DHoTf-09kqeF8tAmbihYzrnopKc-1s5cr"),
                Arguments.of("Slack webhook", "https://hooks.slack.com/services/" + "T00000000/B00000000/" + "XXXXXXXXXXXXXXXXXXXXXXXX"),
                Arguments.of("GitHub fine-grained token", "github" + "_pat_" + "11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz"),
                Arguments.of("JWT", "eyJ" + "hbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiIxMjM0NTY3ODkwIn0" + "." + "dozjgNryP4J3jVmNHl0w5N"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokenCorpus")
    void removesTokenFromCorpus(String format, String token) {
        Redaction redaction = redactor.redact("npm notice publishing with credential " + token + " now");

        assertThat(redaction.text()).doesNotContain(token).contains(MASK);
        assertThat(redaction.secretsRemoved()).isEqualTo(1);
    }

    @Test
    void countsEachSecretOnce() {
        String log = """
                Authorization: Bearer %s
                DB_PASSWORD=hunter2hunter2
                no secrets on this line
                """.formatted(GITHUB_TOKEN);

        Redaction redaction = redactor.redact(log);

        // The token matches both the GitHub token rule and the Authorization header rule.
        assertThat(redaction.secretsRemoved()).isEqualTo(2);
        assertThat(redaction.text()).doesNotContain(GITHUB_TOKEN, "hunter2");
    }

    @Test
    void reportsNothingRemovedForCleanOrMissingText() {
        assertThat(redactor.redact("[INFO] BUILD SUCCESS")).isEqualTo(new Redaction("[INFO] BUILD SUCCESS", 0));
        assertThat(redactor.redact(null)).isEqualTo(new Redaction(null, 0));
    }

    @Test
    void removesCredentialsInUrlsAndHeaders() {
        String log = """
                Connecting to mongodb+srv://admin:Sup3rS3cret@cluster0.example.net/app
                curl -H "Authorization: %s abc.def.ghi-jkl" https://api.example.com
                """.formatted("Bearer");

        String redacted = redactor.redact(log).text();

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
        String redacted = redactor.redact(line).text();

        assertThat(redacted).contains(MASK);
        assertThat(redacted.substring(redacted.indexOf(MASK))).doesNotContainAnyWhitespaces();
        assertThat(redacted).doesNotContain("hunter2", "123456789", "Dating@Web79", "letmein123");
    }

    @Test
    void removesPrivateKeyBlocks() {
        String begin = "-----BEGIN RSA " + "PRIVATE KEY-----";
        String end = "-----END RSA " + "PRIVATE KEY-----";
        String log = "key:\n" + begin + "\nMIIEowIBAAKCAQEAxyz\nabc123\n" + end + "\ndone";

        assertThat(redactor.redact(log).text()).isEqualTo("key:\n" + MASK + "\ndone");
    }

    @Test
    void removesLegacyEncryptedPrivateKeyBlocks() {
        String log = "-----BEGIN RSA " + "PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,3F17F5316E2BAC89\n\n"
                + "hQEMA5hO+vn+bTHMAQf/dGVzdA==\n-----END RSA " + "PRIVATE KEY-----";

        assertThat(redactor.redact(log)).isEqualTo(new Redaction(MASK, 1));
    }

    @Test
    void leavesOrdinaryBuildOutputAlone() {
        String log = """
                [INFO] Tests run: 12, Failures: 1, Errors: 0, Skipped: 0
                [ERROR] PriceCalculatorTest.appliesDiscount:14 expected: <90.0> but was: <0.9>
                token: ***
                Downloading https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.11.0/junit-bom-5.11.0.pom
                sk-learn==1.5.0 and npm_config_cache=/home/runner/.npm
                """;

        assertThat(redactor.redact(log)).isEqualTo(new Redaction(log, 0));
    }

    /** Logs are untrusted: a huge line built to make backtracking regexes explode must still be quick. */
    @ParameterizedTest
    @ValueSource(strings = {"a.", "password", "-----BEGIN PRIVATE KEY-----", "x://a:", "eyJaaaaaaaaa.", "token=",
            "Authorization: Bearer "})
    void handlesPathologicalLinesQuickly(String fragment) {
        String line = fragment.repeat(2 * 1024 * 1024 / fragment.length());

        Redaction redaction = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> redactor.redact(line));

        assertThat(redaction.text()).isNotNull();
    }
}
