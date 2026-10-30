package io.github.raju6302.cifa.worker.logs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.raju6302.cifa.github.model.CheckRunAnnotation;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobTimeoutsTest {

    @Test
    void aTimedOutJobSaysSoOnlyInItsAnnotations() {
        List<CheckRunAnnotation> annotations = Fixtures.annotations("job-timeout");

        assertThat(Fixtures.job("job-timeout").conclusion()).isEqualTo("cancelled");
        assertThat(JobTimeouts.exceededTimeLimit(annotations)).isTrue();
        assertThat(JobTimeouts.limit(annotations)).contains("1m0s");
    }

    @Test
    void aPlainCancelIsNotATimeout() {
        List<CheckRunAnnotation> cancelled = List.of(
                new CheckRunAnnotation(".github", 6, 6, "failure", "", "The operation was canceled."),
                // A notice can't make a job time out, whatever it says.
                new CheckRunAnnotation(".github", 1, 1, "notice", "", "exceeded the maximum execution time"));

        assertThat(JobTimeouts.exceededTimeLimit(cancelled)).isFalse();
        assertThat(JobTimeouts.exceededTimeLimit(Fixtures.annotations("java-test-failure"))).isFalse();
        assertThat(JobTimeouts.timedOut("cancelled", cancelled)).isFalse();
    }

    @Test
    void theLegacyTimedOutConclusionCounts() {
        assertThat(JobTimeouts.timedOut("timed_out", List.of())).isTrue();
        assertThat(JobTimeouts.limit(List.of())).isEmpty();
    }
}
