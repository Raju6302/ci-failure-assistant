package io.github.raju6302.cifa.worker.logs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DockerParserTest {

    DockerParser parser = new DockerParser();

    @Test
    void recognizesDockerBuilds() {
        assertThat(parser.signature(TestLogs.step("Run docker build -t app .", "x"))).isEqualTo(2);
        assertThat(parser.signature(TestLogs.step("Run make", "#3 [internal] load .dockerignore"))).isEqualTo(2);
        assertThat(parser.signature(TestLogs.step("Run make", "#3 DONE 0.1s"))).isEqualTo(1);
        assertThat(parser.signature(TestLogs.step("Run make", "all good"))).isZero();
    }

    @Test
    void aMissingBaseImageIsADependencyProblem() {
        StepLog step = TestLogs.step("Run docker build .", """
                #2 [internal] load metadata for docker.io/library/pyhton:3.13
                #2 ERROR: pull access denied, repository does not exist or may require authorization
                ------
                 > [internal] load metadata for docker.io/library/pyhton:3.13:
                ------
                ERROR: failed to solve: pyhton:3.13: failed to resolve source metadata for docker.io/library/pyhton:3.13
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.DEPENDENCY);
        assertThat(finding.summary()).startsWith("[internal] load metadata for docker.io/library/pyhton:3.13: ");
        assertThat(finding.firstLine()).isEqualTo(2);
        assertThat(finding.lastLine()).isEqualTo(6);
    }

    @Test
    void aFailedRunInstructionPointsAtTheDockerfileLine() {
        StepLog step = TestLogs.step("Run docker build -f docker/Dockerfile.prod .", """
                #9 [4/5] RUN npm ci
                #9 ERROR: process "/bin/sh -c npm ci" did not complete successfully: exit code: 1
                ------
                 > [4/5] RUN npm ci:
                ------
                docker/Dockerfile.prod:4
                ERROR: failed to solve: process "/bin/sh -c npm ci" did not complete successfully: exit code: 1
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(finding.summary())
                .isEqualTo("[4/5] RUN npm ci: process \"/bin/sh -c npm ci\" did not complete successfully: exit code: 1");
        assertThat(finding.codeReferences()).containsExactly(new CodeReference("docker/Dockerfile.prod", 4));
    }

    @Test
    void readsTheClassicBuilder() {
        StepLog step = TestLogs.step("Run docker build .",
                "COPY failed: file not found in build context or excluded by .dockerignore: stat requirements.txt: file does not exist");

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.CONFIG_ERROR);
        assertThat(finding.summary()).startsWith("Docker build failed: COPY failed: file not found in build context");
    }
}
