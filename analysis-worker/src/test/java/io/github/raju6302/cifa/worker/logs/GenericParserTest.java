package io.github.raju6302.cifa.worker.logs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GenericParserTest {

    GenericParser parser = new GenericParser();

    @Test
    void takesTheFirstErrorLine() {
        StepLog step = TestLogs.step("Run git checkout release", """
                Switching branches
                error: pathspec 'release' did not match any file(s) known to git
                ##[error]Process completed with exit code 1.
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(finding.summary()).isEqualTo("error: pathspec 'release' did not match any file(s) known to git");
        assertThat(finding.specific()).isTrue();
        assertThat(finding.firstLine()).isEqualTo(2);
    }

    @Test
    void anActionMissingAnInputIsAConfigError() {
        Finding finding = parser.parse(TestLogs.step("Run some/action@v1",
                "##[error]Input required and not supplied: token")).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.CONFIG_ERROR);
        assertThat(finding.summary()).isEqualTo("Input required and not supplied: token");
    }

    @Test
    void withoutAnErrorLineUsesTheLastOutputAndExitCode() {
        StepLog step = TestLogs.step("Run ./deploy.sh", """
                ##[group]Run ./deploy.sh
                ./deploy.sh
                ##[endgroup]
                Deploying to staging
                ./deploy.sh: line 3: kubectl: command not found
                ##[error]Process completed with exit code 127.
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.CONFIG_ERROR);
        assertThat(finding.summary())
                .isEqualTo("\"Run ./deploy.sh\" exited with code 127; last output: ./deploy.sh: line 3: kubectl: command not found");
        assertThat(finding.specific()).isFalse();
        assertThat(finding.lastLine()).isEqualTo(6);
    }

    @Test
    void recognizesTheGenericAftermathLines() {
        assertThat(GenericParser.isAftermath("Process completed with exit code 1.")).isTrue();
        assertThat(GenericParser.isAftermath("The operation was canceled.")).isTrue();
        assertThat(GenericParser.isAftermath("cannot find symbol")).isFalse();
    }
}
