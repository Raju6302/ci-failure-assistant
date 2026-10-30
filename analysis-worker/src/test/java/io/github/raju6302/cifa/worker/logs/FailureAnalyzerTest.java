package io.github.raju6302.cifa.worker.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.raju6302.cifa.github.model.CheckRunAnnotation;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class FailureAnalyzerTest {

    static final CheckRunAnnotation EXIT_CODE =
            new CheckRunAnnotation(".github", 60, 60, "failure", "", "Process completed with exit code 1.");
    static final CheckRunAnnotation TIMEOUT =
            new CheckRunAnnotation(".github", 1, 1, "failure", "", "The job has exceeded the maximum execution time of 30m0s");

    FailureAnalyzer analyzer = new FailureAnalyzer();

    @Test
    void anExpiredLogIsAnalyzedFromAnnotations() {
        CheckRunAnnotation compilerError =
                new CheckRunAnnotation("src/main/java/shop/InvoiceService.java", 9, 9, "failure", "", "cannot find symbol");
        CheckRunAnnotation notice = new CheckRunAnnotation(".github", 1, 1, "notice", "", "ubuntu-latest is moving");

        FailureAnalysis analysis = analyzer.analyze(
                new JobEvidence("failure", List.of("Build"), null, List.of(EXIT_CODE, compilerError, notice)));

        assertThat(analysis.category()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(analysis.parser()).isEqualTo(FailureAnalyzer.ANNOTATIONS_PARSER);
        assertThat(analysis.failedStep()).isEqualTo("Build");
        assertThat(analysis.summary()).isEqualTo("src/main/java/shop/InvoiceService.java:9: cannot find symbol");
        assertThat(analysis.excerpt()).isEqualTo(
                "Process completed with exit code 1.\nsrc/main/java/shop/InvoiceService.java:9: cannot find symbol");
        assertThat(analysis.excerptStartLine()).isNull();
        assertThat(analysis.excerptEndLine()).isNull();
        assertThat(analysis.codeReferences()).containsExactly(new CodeReference("src/main/java/shop/InvoiceService.java", 9));
    }

    @Test
    void aTimeoutWithoutLogIsStillInfrastructure() {
        FailureAnalysis analysis = analyzer.analyze(new JobEvidence("cancelled", List.of("Integration tests"), null, List.of(TIMEOUT)));

        assertThat(analysis.category()).isEqualTo(FailureCategory.INFRASTRUCTURE);
        assertThat(analysis.summary()).isEqualTo("Job timed out after 30m0s in \"Integration tests\"");
    }

    @Test
    void withNeitherLogNorAnnotationsTheFailureIsUnknown() {
        FailureAnalysis analysis = analyzer.analyze(new JobEvidence("failure", List.of("Deploy"), null, List.of()));

        assertThat(analysis.category()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(analysis.summary()).isEqualTo("No log or error annotations available; failed step: \"Deploy\"");
        assertThat(analysis.excerpt()).isEmpty();
        assertThat(analysis.parser()).isEqualTo("none");
    }

    @Test
    void aVagueLogFallsBackToAnAnnotationWithAFile() {
        CheckRunAnnotation lintError = new CheckRunAnnotation("src/app.js", 3, 3, "failure", "", "Unexpected token '}'");
        String log = TestLogs.jobLog("./lint.sh", "Linting 12 files");

        FailureAnalysis analysis = analyzer.analyze(new JobEvidence("failure", List.of("Lint"), log, List.of(EXIT_CODE, lintError)));

        assertThat(analysis.parser()).isEqualTo("generic");
        assertThat(analysis.summary()).isEqualTo("src/app.js:3: Unexpected token '}'");
        assertThat(analysis.codeReferences()).containsExactly(new CodeReference("src/app.js", 3));
    }

    @Test
    void runnerProblemsAreInfrastructureWhateverTheTool() {
        FailureAnalysis analysis = analyzer.analyze(TestLogs.evidence("mvn verify", """
                [INFO] Running shop.ReportTest
                [ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 9 s <<< FAILURE! -- in shop.ReportTest
                [ERROR] shop.ReportTest.rendersYear -- Time elapsed: 9 s <<< ERROR!
                java.lang.OutOfMemoryError: Java heap space
                """));

        assertThat(analysis.category()).isEqualTo(FailureCategory.INFRASTRUCTURE);
        assertThat(analysis.parser()).isEqualTo("maven");
        assertThat(analysis.summary()).contains("OutOfMemoryError");
    }

    @Test
    void aDownloadThatFailedOnTheNetworkIsInfrastructure() {
        FailureAnalysis analysis = analyzer.analyze(TestLogs.evidence("mvn verify", """
                [ERROR] Failed to execute goal on project app: Could not resolve dependencies for project shop:app:jar:1.0
                [ERROR] Could not transfer artifact org.junit:junit-bom:pom:5.11.0 from/to central: Connection timed out
                """));

        assertThat(analysis.category()).isEqualTo(FailureCategory.INFRASTRUCTURE);
        assertThat(analysis.summary()).startsWith("Could not transfer artifact org.junit:junit-bom:pom:5.11.0");
    }

    @Test
    void aTestIsFlakyOnlyWhenTheTestToolSaysSo() {
        FailureAnalysis retried = analyzer.analyze(TestLogs.evidence("pytest -p rerunfailures --reruns 2", """
                =================================== FAILURES ===================================
                FAILED tests/test_cache.py::test_warm - assert False
                ============ 1 failed, 3 passed, 2 rerun in 0.52s ============
                """));
        FailureAnalysis notRetried = analyzer.analyze(TestLogs.evidence("pytest", """
                FAILED tests/test_flaky_cache.py::test_flaky_warmup - assert False
                ============ 1 failed, 3 passed in 0.52s ============
                """));

        assertThat(retried.category()).isEqualTo(FailureCategory.FLAKY_TEST);
        assertThat(notRetried.category()).isEqualTo(FailureCategory.TEST_FAILURE);
    }

    @Test
    void surefireFlakesAreFlaky() {
        FailureAnalysis analysis = analyzer.analyze(TestLogs.evidence("mvn verify -Dsurefire.rerunFailingTestsCount=2", """
                [ERROR] shop.CacheTest.warms -- Time elapsed: 0.1 s <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected: <true> but was: <false>
                [WARNING] Flakes:
                [WARNING] shop.CacheTest.warms
                [ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0, Flakes: 1
                """));

        assertThat(analysis.category()).isEqualTo(FailureCategory.FLAKY_TEST);
    }

    @Test
    void aLogWithoutStepMarkersIsStillAnalyzed() {
        FailureAnalysis analysis = analyzer.analyze(
                new JobEvidence("failure", List.of(), "Starting\nError: Cannot connect to the Docker daemon\n", List.of()));

        assertThat(analysis.summary()).isEqualTo("Error: Cannot connect to the Docker daemon");
        assertThat(analysis.failedStep()).isNull();
        assertThat(analysis.excerptStartLine()).isEqualTo(1);
        assertThat(analysis.excerptEndLine()).isEqualTo(2);
    }

    /** Logs are untrusted: lines crafted to make the parsers' patterns backtrack must not stall ingestion. */
    @Test
    void pathologicalLinesDoNotStallTheAnalysis() {
        String log = pathologicalLog();

        FailureAnalysis analysis = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> analyzer.analyze(new JobEvidence("failure", List.of(), log, List.of())));

        assertThat(analysis.excerpt()).hasSizeLessThanOrEqualTo(Excerpts.MAX_CHARS);
        assertThat(analysis.summary()).hasSizeLessThanOrEqualTo(Finding.MAX_SUMMARY_LENGTH);
    }

    /** The analyzer stops at the first parser with a finding, so each parser also gets the log on its own. */
    @Test
    void everyParserHandlesPathologicalLinesQuickly() {
        StepLog step = new StepLog("Run mvn verify", LogNormalizer.normalize(pathologicalLog()));
        List<LogParser> parsers = List.of(new MavenParser(), new GradleParser(), new NodeParser(), new PythonParser(),
                new DockerParser(), new GenericParser());

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> parsers.forEach(parser -> {
            parser.signature(step);
            parser.parse(step);
        }));
    }

    private static String pathologicalLog() {
        int size = 2 * 1024 * 1024;
        return String.join("\n",
                "##[group]Run mvn verify && npm test && pytest && docker build .",
                "##[endgroup]",
                "[ERROR] " + "/a".repeat(size / 2),
                "[ERROR] Tests run: 1, Failures: 1, Errors: 0 " + " ".repeat(size),
                "[ERROR] " + "a.".repeat(size / 2) + " -- Time elapsed",
                "\tat " + "a.".repeat(size / 2),
                "    at " + "a (".repeat(size / 3),
                "✖ " + "x (".repeat(size / 3),
                "test at " + "a:1".repeat(size / 3),
                "FAILED " + "a::".repeat(size / 3),
                "File \"" + "a".repeat(size),
                "#1 ERROR: " + "\"a\": ".repeat(size / 5),
                " > [" + "a".repeat(size),
                "Could not find " + "a:".repeat(size / 2),
                "Error: " + "e".repeat(size),
                "##[error]Process completed with exit code 1.");
    }
}
