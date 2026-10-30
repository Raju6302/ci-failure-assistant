package io.github.raju6302.cifa.worker.logs;

import io.github.raju6302.cifa.github.model.CheckRunAnnotation;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes jobs that hit their {@code timeout-minutes}. GitHub reports those with
 * conclusion {@code cancelled}, exactly like a manual or fail-fast cancel; only the
 * check-run annotation says the time limit was exceeded.
 */
public final class JobTimeouts {

    private static final Pattern EXCEEDED =
            Pattern.compile("exceeded the maximum execution time(?: of ([\\w.]{1,20}))?", Pattern.CASE_INSENSITIVE);

    private JobTimeouts() {
    }

    public static boolean exceededTimeLimit(List<CheckRunAnnotation> annotations) {
        return timeoutAnnotation(annotations).isPresent();
    }

    /** True for a job that ran out of time, whichever way GitHub reported it. */
    static boolean timedOut(String conclusion, List<CheckRunAnnotation> annotations) {
        return "timed_out".equals(conclusion) || exceededTimeLimit(annotations);
    }

    /** The limit as GitHub printed it, e.g. {@code 1m0s}. */
    static Optional<String> limit(List<CheckRunAnnotation> annotations) {
        return timeoutAnnotation(annotations).map(annotation -> {
            Matcher matcher = EXCEEDED.matcher(annotation.message());
            return matcher.find() ? matcher.group(1) : null;
        });
    }

    private static Optional<CheckRunAnnotation> timeoutAnnotation(List<CheckRunAnnotation> annotations) {
        return annotations.stream()
                .filter(CheckRunAnnotation::isFailure)
                .filter(annotation -> annotation.message() != null && EXCEEDED.matcher(annotation.message()).find())
                .findFirst();
    }
}
