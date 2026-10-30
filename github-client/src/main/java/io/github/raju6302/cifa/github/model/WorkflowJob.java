package io.github.raju6302.cifa.github.model;

import java.time.Instant;
import java.util.List;

/** A job inside a workflow run, as returned by the Actions API. */
public record WorkflowJob(
        long id,
        String name,
        String status,
        String conclusion,
        String htmlUrl,
        Instant startedAt,
        Instant completedAt,
        List<Step> steps) {

    public record Step(int number, String name, String conclusion) {
    }

    public List<String> failedStepNames() {
        return steps == null ? List.of() : steps.stream()
                .filter(step -> "failure".equals(step.conclusion()) || "timed_out".equals(step.conclusion()))
                .map(Step::name)
                .toList();
    }
}
