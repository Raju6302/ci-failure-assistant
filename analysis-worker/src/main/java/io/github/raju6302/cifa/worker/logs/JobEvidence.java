package io.github.raju6302.cifa.worker.logs;

import io.github.raju6302.cifa.github.model.CheckRunAnnotation;
import java.util.List;

/**
 * Everything known about a failed job, already redacted.
 *
 * @param conclusion  GitHub's job conclusion, e.g. {@code failure} or {@code cancelled}
 * @param failedSteps names of the steps GitHub marked as failed (or cancelled, for a timeout)
 * @param log         the stored log text, or null when GitHub no longer had it
 * @param annotations the job's check-run annotations
 */
public record JobEvidence(String conclusion, List<String> failedSteps, String log, List<CheckRunAnnotation> annotations) {

    public JobEvidence {
        failedSteps = failedSteps == null ? List.of() : List.copyOf(failedSteps);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    boolean hasLog() {
        return log != null && !log.isBlank();
    }

    List<CheckRunAnnotation> failureAnnotations() {
        return annotations.stream().filter(CheckRunAnnotation::isFailure).toList();
    }
}
