package io.github.raju6302.cifa.worker.logs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LogStepsTest {

    static final String LOG = """
            Current runner version: '2.338.0'
            ##[group]Run actions/checkout@v7
            Syncing repository
            ##[endgroup]
            ##[group]Run ./lint.sh
            ##[endgroup]
            ##[error]lint warning promoted to error
            ##[group]Run mvn -B verify
            ##[endgroup]
            [ERROR] BUILD FAILURE
            ##[error]Process completed with exit code 1.
            Post job cleanup.
            Post job cleanup.
            [command]/usr/bin/git version
            """;

    @Test
    void splitsAtStepMarkersAndKeepsLineNumbers() {
        List<StepLog> steps = LogSteps.split(LogNormalizer.normalize(LOG));

        assertThat(steps).extracting(StepLog::name).containsExactly(
                LogSteps.SET_UP_JOB, "Run actions/checkout@v7", "Run ./lint.sh", "Run mvn -B verify", LogSteps.POST_JOB);
        StepLog maven = steps.get(3);
        assertThat(maven.lines().getFirst().number()).isEqualTo(8);
        assertThat(maven.lines().getLast().number()).isEqualTo(11);
        assertThat(maven.output()).extracting(LogLine::content)
                .containsExactly("[ERROR] BUILD FAILURE", "Process completed with exit code 1.");
    }

    @Test
    void failingStepIsTheLastWithAnErrorUnlessGitHubNamesOne() {
        List<StepLog> steps = LogSteps.split(LogNormalizer.normalize(LOG));

        assertThat(LogSteps.failing(steps, List.of())).get().extracting(StepLog::name).isEqualTo("Run mvn -B verify");
        assertThat(LogSteps.failing(steps, List.of("Run ./lint.sh"))).get().extracting(StepLog::name).isEqualTo("Run ./lint.sh");
    }

    @Test
    void withoutErrorLinesTheLastStepThatRanFailed() {
        String log = "##[group]Run ./a.sh\n##[endgroup]\nok\n##[group]Run ./slow.sh\n##[endgroup]\nwaiting...\nPost job cleanup.\n";

        List<StepLog> steps = LogSteps.split(LogNormalizer.normalize(log));

        assertThat(LogSteps.failing(steps, List.of())).get().extracting(StepLog::name).isEqualTo("Run ./slow.sh");
    }

    @Test
    void aLogWithoutMarkersIsOneUnnamedStep() {
        List<StepLog> steps = LogSteps.split(LogNormalizer.normalize("line one\nError: broke\n"));

        assertThat(steps).singleElement().satisfies(step -> {
            assertThat(step.name()).isNull();
            assertThat(step.lines()).hasSize(2);
        });
        assertThat(LogSteps.failing(steps, List.of("Build"))).isPresent();
    }

    @Test
    void displayNamePrefersGitHubsStepName() {
        StepLog step = TestLogs.step("Run mvn -B verify", "x");

        assertThat(LogSteps.displayName(step, List.of("Build and test"))).isEqualTo("Build and test");
        assertThat(LogSteps.displayName(step, List.of("Other", "Run mvn -B verify"))).isEqualTo("Run mvn -B verify");
        assertThat(LogSteps.displayName(step, List.of())).isEqualTo("Run mvn -B verify");
        assertThat(LogSteps.displayName(null, List.of())).isNull();
    }
}
