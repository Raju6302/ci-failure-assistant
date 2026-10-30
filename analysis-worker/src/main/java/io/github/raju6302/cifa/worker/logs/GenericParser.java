package io.github.raju6302.cifa.worker.logs;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Fallback for any tool: the first error-looking line of the failing step, or, when there
 * is none, the step's last lines and its exit code. Always produces a finding.
 */
final class GenericParser implements LogParser {

    private static final Pattern ERROR_LINE =
            Pattern.compile("^\\s{0,8}(?:error|fatal|failure)(?:\\[[^\\]]{0,50}\\])?\\s{0,4}[:!]\\s{0,4}\\S", Pattern.CASE_INSENSITIVE);
    // Consequences of a failure rather than its cause.
    private static final Pattern AFTERMATH = Pattern.compile(
            "^(?:Error: )?Process completed with exit code \\d{1,5}\\.?$|^The operation was canceled\\.?$");
    private static final Pattern EXIT_CODE = Pattern.compile("exit code (?<code>\\d{1,5})");
    private static final Pattern CONFIG_PROBLEM = Pattern.compile(
            "command not found|: not found$|No such file or directory|Permission denied|Invalid workflow file"
                    + "|Unable to resolve action|Input required and not supplied|Unrecognized named-value"
                    + "|(?:environment variable|env var|secret)\\b.{0,80}\\b(?:is not set|is missing|is required|not defined)",
            Pattern.CASE_INSENSITIVE);

    private static final int TAIL_LINES = 8;
    private static final int MAX_ERROR_SPAN = 15;

    @Override
    public String name() {
        return "generic";
    }

    @Override
    public int signature(StepLog step) {
        return 1;
    }

    @Override
    public Optional<Finding> parse(StepLog step) {
        List<LogLine> errors = step.lines().stream()
                .filter(line -> line.isError() || ERROR_LINE.matcher(line.content()).find())
                .filter(line -> !AFTERMATH.matcher(line.content()).find())
                .toList();
        if (errors.isEmpty()) {
            return Optional.of(tail(step));
        }
        LogLine first = errors.getFirst();
        int last = errors.stream().mapToInt(LogLine::number)
                .filter(number -> number <= first.number() + MAX_ERROR_SPAN)
                .max().orElse(first.number());
        String error = first.content().strip();
        return Optional.of(new Finding(category(step), error, first.number(), last, List.of(error), List.of()));
    }

    /**
     * The step's last output lines. Used when nothing looks like an error, and for jobs that
     * were cut off by a timeout, where what the step was doing last is the best clue.
     */
    static Finding tail(StepLog step) {
        List<LogLine> output = step.output().stream()
                .filter(line -> !line.isBlank() && line.command() == null && !AFTERMATH.matcher(line.content()).find())
                .toList();
        String exitCode = step.find(EXIT_CODE).map(matcher -> matcher.group("code")).orElse(null);
        String stepName = step.name() == null ? "The job" : "\"" + step.name() + "\"";
        String lastOutput = output.isEmpty() ? null : output.getLast().content().strip();
        String summary = (exitCode == null ? stepName + " failed" : stepName + " exited with code " + exitCode)
                + (lastOutput == null ? "" : "; last output: " + lastOutput);
        LogLine end = step.lines().getLast();
        int start = output.size() > TAIL_LINES ? output.get(output.size() - TAIL_LINES).number() : step.lines().getFirst().number();
        return new Finding(category(step), summary, start, end.number(), lastOutput == null ? List.of() : List.of(lastOutput),
                List.of(), false);
    }

    private static FailureCategory category(StepLog step) {
        return step.contains(CONFIG_PROBLEM) ? FailureCategory.CONFIG_ERROR : FailureCategory.UNKNOWN;
    }

    /** True for "Process completed with exit code 1." and the like, which say that something failed but not what. */
    static boolean isAftermath(String text) {
        return AFTERMATH.matcher(text.strip()).find();
    }
}
