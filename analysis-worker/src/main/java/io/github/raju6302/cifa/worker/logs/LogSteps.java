package io.github.raju6302.cifa.worker.logs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Splits a job log into steps and finds the one that failed.
 *
 * <p>GitHub opens each step with {@code ##[group]Run <command or action>} and runs post-job
 * cleanup after a {@code Post job cleanup.} line. The log names a step by its command, while
 * the job metadata uses the step's {@code name:}, so the two only sometimes match.
 */
final class LogSteps {

    static final String SET_UP_JOB = "Set up job";
    static final String POST_JOB = "Post job cleanup";
    private static final String STEP_PREFIX = "Run ";
    private static final String POST_JOB_LINE = "Post job cleanup.";

    private LogSteps() {
    }

    static List<StepLog> split(List<LogLine> lines) {
        List<StepLog> steps = new ArrayList<>();
        String name = null;
        int start = 0;
        for (int i = 0; i < lines.size(); i++) {
            LogLine line = lines.get(i);
            String next = stepStartedBy(line);
            if (next == null || POST_JOB.equals(next) && POST_JOB.equals(name)) {
                continue;
            }
            if (i > start) {
                steps.add(new StepLog(name == null ? SET_UP_JOB : name, lines.subList(start, i)));
            }
            name = next;
            start = i;
        }
        if (start < lines.size()) {
            boolean noMarkers = steps.isEmpty() && name == null;
            steps.add(new StepLog(noMarkers ? null : name == null ? SET_UP_JOB : name, lines.subList(start, lines.size())));
        }
        return steps;
    }

    /**
     * The step that failed the job: one with an {@code ##[error]} line, preferring a step whose
     * name GitHub lists as failed, otherwise the last one (earlier errors may come from steps
     * with {@code continue-on-error}). Without any error line, the last step that ran: the job
     * was probably cancelled in the middle of it.
     */
    static Optional<StepLog> failing(List<StepLog> steps, List<String> failedStepNames) {
        List<StepLog> candidates = steps.stream().filter(LogSteps::isUserStep).toList();
        List<StepLog> withErrors = candidates.stream().filter(StepLog::hasErrors).toList();
        Optional<StepLog> named = withErrors.stream().filter(step -> isListed(step, failedStepNames)).findFirst();
        if (named.isPresent()) {
            return named;
        }
        if (!withErrors.isEmpty()) {
            return Optional.of(withErrors.getLast());
        }
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.getLast());
    }

    /**
     * How to show the failed step: GitHub's name when the job metadata has one (that's what the
     * Actions UI shows), otherwise the name from the log.
     */
    static String displayName(StepLog step, List<String> failedStepNames) {
        if (step != null && isListed(step, failedStepNames)) {
            return step.name();
        }
        if (!failedStepNames.isEmpty()) {
            return failedStepNames.getFirst();
        }
        return step == null ? null : step.name();
    }

    private static boolean isListed(StepLog step, List<String> failedStepNames) {
        return step.name() != null && failedStepNames.contains(step.name());
    }

    private static boolean isUserStep(StepLog step) {
        return !SET_UP_JOB.equals(step.name()) && !POST_JOB.equals(step.name());
    }

    private static String stepStartedBy(LogLine line) {
        if (line.isGroupStart() && line.content().startsWith(STEP_PREFIX)) {
            return line.content();
        }
        return line.command() == null && POST_JOB_LINE.equals(line.content()) ? POST_JOB : null;
    }
}
