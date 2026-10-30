package io.github.raju6302.cifa.worker.diagnosis;

import java.util.List;

/** A failed job without a diagnosis yet, with its Phase 2 analysis (null if none). */
public record JobToDiagnose(long failedJobId, long githubJobId, String name, String conclusion,
                            List<String> failedSteps, FailureContext.Analysis analysis) {
}
