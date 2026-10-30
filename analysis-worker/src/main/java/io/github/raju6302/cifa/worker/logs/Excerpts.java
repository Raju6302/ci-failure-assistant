package io.github.raju6302.cifa.worker.logs;

import java.util.List;

/**
 * Cuts the lines a finding points at, plus a little context, out of the failing step. The
 * excerpt is shown in the dashboard and later goes into the LLM prompt, so it is bounded to
 * {@value #MAX_LINES} lines and about {@value #MAX_CHARS} characters, keeping the start (the
 * first error is usually the cause, later ones its consequences).
 */
final class Excerpts {

    record Excerpt(String text, int startLine, int endLine) {
    }

    static final int MAX_LINES = 60;
    static final int MAX_CHARS = 6_000;
    static final int CONTEXT_BEFORE = 3;
    static final int CONTEXT_AFTER = 3;

    private Excerpts() {
    }

    static Excerpt of(StepLog step, int firstLine, int lastLine) {
        List<LogLine> lines = step.lines();
        int stepStart = lines.getFirst().number();
        int stepEnd = lines.getLast().number();
        // Context stops at the step's header (command, shell, env) unless the finding is in it.
        List<LogLine> output = step.output();
        int outputStart = output.isEmpty() ? stepStart : output.getFirst().number();
        int contextStart = firstLine >= outputStart ? outputStart : stepStart;
        int from = Math.max(contextStart, Math.min(firstLine, stepEnd) - CONTEXT_BEFORE);
        int to = Math.min(stepEnd, Math.max(lastLine, firstLine) + CONTEXT_AFTER);

        StringBuilder text = new StringBuilder();
        int end = from - 1;
        for (int number = from; number <= to && number - from < MAX_LINES; number++) {
            String content = lines.get(number - stepStart).content();
            if (end >= from && text.length() + content.length() + 1 > MAX_CHARS) {
                break;
            }
            if (end >= from) {
                text.append('\n');
            }
            text.append(content.length() > MAX_CHARS ? content.substring(0, MAX_CHARS) : content);
            end = number;
        }
        return new Excerpt(text.toString(), from, end);
    }
}
