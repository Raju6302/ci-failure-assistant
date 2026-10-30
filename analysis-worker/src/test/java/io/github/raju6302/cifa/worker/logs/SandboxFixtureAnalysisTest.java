package io.github.raju6302.cifa.worker.logs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The analyzer against real logs of the sandbox projects (Phase 2's "done when": classified
 * correctly, without any AI). Expected categories match {@code evals/sandbox-expected.json},
 * except the flaky test: one failed run can't prove flakiness, so it is a TEST_FAILURE here.
 */
class SandboxFixtureAnalysisTest {

    FailureAnalyzer analyzer = new FailureAnalyzer();

    static Stream<Arguments> scenarios() {
        return Stream.of(
                Arguments.of("java-compile-error", FailureCategory.COMPILE_ERROR, "maven",
                        List.of("InvoiceService", "price()"),
                        "sandbox-repos/java-compile-error/src/main/java/shop/InvoiceService.java"),
                // The log only shows the test's stack trace, not the class under test.
                Arguments.of("java-test-failure", FailureCategory.TEST_FAILURE, "maven",
                        List.of("appliesPercentageDiscount", "90.0", "-900.0"),
                        "sandbox-repos/java-test-failure/src/test/java/shop/PriceCalculatorTest.java"),
                Arguments.of("java-missing-dependency", FailureCategory.DEPENDENCY, "maven",
                        List.of("commons-lang4"), "pom.xml"),
                Arguments.of("node-test-failure", FailureCategory.TEST_FAILURE, "node",
                        List.of("collapses punctuation", "'ci-is-broken-again'", "'ci-is broken, again!'"),
                        "sandbox-repos/node-test-failure/slugify.test.js"),
                Arguments.of("node-flaky-test", FailureCategory.TEST_FAILURE, "node",
                        List.of("cache is warm after 50ms"), "sandbox-repos/node-flaky-test/cache.test.js"),
                Arguments.of("python-test-failure", FailureCategory.TEST_FAILURE, "python",
                        List.of("test_hours_and_minutes", "assert 31 == 5400"), "test_durations.py"),
                Arguments.of("docker-build-failure", FailureCategory.CONFIG_ERROR, "docker",
                        List.of("requirements.txt", "COPY"), "Dockerfile"),
                Arguments.of("job-timeout", FailureCategory.INFRASTRUCTURE, "timeout",
                        List.of("timed out after 1m0s", "Build and test", "payments-service"), null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void classifiesTheRealFailure(String scenario, FailureCategory category, String parser, List<String> summaryMentions,
                                  String culprit) {
        FailureAnalysis analysis = analyzer.analyze(Fixtures.evidence(scenario));

        assertThat(analysis.category()).isEqualTo(category);
        assertThat(analysis.parser()).isEqualTo(parser);
        assertThat(analysis.failedStep()).isEqualTo("Build and test");
        assertThat(analysis.summary()).containsIgnoringCase(summaryMentions.getFirst());
        summaryMentions.forEach(mention -> assertThat(analysis.summary()).containsIgnoringCase(mention));
        assertThat(analysis.summary()).doesNotContain("\n").hasSizeLessThanOrEqualTo(Finding.MAX_SUMMARY_LENGTH);
        if (culprit != null) {
            assertThat(analysis.codeReferences()).extracting(CodeReference::path).contains(culprit);
        }
        assertThat(analysis.codeReferences()).extracting(CodeReference::path)
                .allSatisfy(path -> assertThat(path).doesNotStartWith("/").doesNotContain("/home/runner"));
        assertThat(analysis.fingerprint()).matches("[0-9a-f]{64}");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void excerptIsBoundedAndCitesOriginalLineNumbers(String scenario) {
        String log = Fixtures.log(scenario);
        FailureAnalysis analysis = analyzer.analyze(Fixtures.evidence(scenario));

        List<String> excerpt = analysis.excerpt().lines().toList();
        assertThat(excerpt).hasSizeLessThanOrEqualTo(Excerpts.MAX_LINES)
                .hasSize(analysis.excerptEndLine() - analysis.excerptStartLine() + 1);
        assertThat(analysis.excerpt()).hasSizeLessThanOrEqualTo(Excerpts.MAX_CHARS).doesNotContain("\u001B", "##[error]");
        List<String> logLines = log.lines().toList();
        for (int i = 0; i < excerpt.size(); i++) {
            // Each excerpt line is the stored log line it cites, minus timestamp and markup.
            assertThat(logLines.get(analysis.excerptStartLine() - 1 + i)).contains(excerpt.get(i).strip());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void excerptContainsTheError(String scenario) {
        FailureAnalysis analysis = analyzer.analyze(Fixtures.evidence(scenario));

        String expected = switch (scenario) {
            case "java-compile-error" -> "cannot find symbol";
            case "java-test-failure" -> "expected: <90.0> but was: <-900.0>";
            case "java-missing-dependency" -> "Could not find artifact org.apache.commons:commons-lang4";
            case "node-test-failure", "node-flaky-test" -> "AssertionError";
            case "python-test-failure" -> "FAILED test_durations.py::test_hours_and_minutes";
            case "docker-build-failure" -> "\"/requirements.txt\": not found";
            case "job-timeout" -> "Waiting for payments-service";
            default -> throw new IllegalArgumentException(scenario);
        };
        assertThat(analysis.excerpt()).contains(expected);
    }
}
