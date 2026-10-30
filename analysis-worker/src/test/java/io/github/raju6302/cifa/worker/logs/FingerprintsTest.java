package io.github.raju6302.cifa.worker.logs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FingerprintsTest {

    FailureAnalyzer analyzer = new FailureAnalyzer();

    @Test
    void theSameFailureOnAnotherCommitGetsTheSameFingerprint() {
        JobEvidence original = Fixtures.evidence("java-test-failure");
        // Another run: other day, a fork with another name, the test moved down two lines, a different wrong value.
        String rerun = original.log()
                .replace("2026-10-30T13:", "2026-11-02T08:")
                .replace("/home/runner/work/ci-failure-assistant/ci-failure-assistant/", "/home/runner/work/shop-fork/shop-fork/")
                .replace("PriceCalculatorTest.java:13)", "PriceCalculatorTest.java:15)")
                .replace("appliesPercentageDiscount:13", "appliesPercentageDiscount:15")
                .replace("-900.0", "-905.0")
                .replace("Time elapsed: 0.028 s", "Time elapsed: 0.031 s");

        FailureAnalysis first = analyzer.analyze(original);
        FailureAnalysis second = analyzer.analyze(new JobEvidence("failure", original.failedSteps(), rerun, original.annotations()));

        assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
    }

    @Test
    void differentFailuresGetDifferentFingerprints() {
        List<String> fingerprints = List.of("java-compile-error", "java-test-failure", "java-missing-dependency",
                        "node-test-failure", "node-flaky-test", "python-test-failure", "docker-build-failure", "job-timeout")
                .stream()
                .map(scenario -> analyzer.analyze(Fixtures.evidence(scenario)).fingerprint())
                .toList();

        assertThat(fingerprints).doesNotHaveDuplicates();
    }

    @Test
    void wordsMatterButVolatileDetailsDoNot() {
        String error = "src/main/java/shop/InvoiceService.java:[9,34] cannot find symbol: method price()";

        assertThat(Fingerprints.of(FailureCategory.COMPILE_ERROR, List.of(error)))
                .isEqualTo(Fingerprints.of(FailureCategory.COMPILE_ERROR,
                        List.of("src/main/java/shop/InvoiceService.java:[12,8] cannot find symbol: method price()")))
                .isNotEqualTo(Fingerprints.of(FailureCategory.COMPILE_ERROR,
                        List.of("src/main/java/shop/InvoiceService.java:[9,34] cannot find symbol: method total()")))
                .isNotEqualTo(Fingerprints.of(FailureCategory.COMPILE_ERROR,
                        List.of("src/main/java/shop/OrderService.java:[9,34] cannot find symbol: method price()")))
                .isNotEqualTo(Fingerprints.of(FailureCategory.TEST_FAILURE, List.of(error)));
    }

    @Test
    void masksTimestampsIdsTempPathsAndNumbers() {
        assertThat(Fingerprints.normalize(
                "2026-10-30T13:29:50.5453002Z /home/runner/work/_temp/84502f30-ab09-4bc2-8eee-0d4b22cef72c.sh: line 3: "
                        + "ref z4u0zbs8jcmp9l1t942l3qm2u at commit ac81791f9c7df2781f99465b3f25f5ce2e406817 took 12.5s"))
                .isEqualTo("<time> <tmp> line <n>: ref <id> at commit <hex> took <n>s");
        assertThat(Fingerprints.normalize("/home/runner/work/shop/shop/src/App.java:[9,34]  error"))
                .isEqualTo("src/App.java:[<n>,<n>] error");
        // Digits inside names identify things; only standalone numbers are masked.
        assertThat(Fingerprints.normalize("commons-lang4:jar:3.17.0")).isEqualTo("commons-lang4:jar:<n>");
    }
}
