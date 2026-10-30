package io.github.raju6302.cifa.worker.logs;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lines one step wrote.
 *
 * @param name the step as the log names it, e.g. {@code Run mvn -B verify}; null when the log
 *             has no step markers
 */
record StepLog(String name, List<LogLine> lines) {

    StepLog {
        lines = List.copyOf(lines);
    }

    /**
     * What the step printed, without the header GitHub writes first (the command, shell and
     * env, between {@code ##[group]} and the first {@code ##[endgroup]}).
     */
    List<LogLine> output() {
        if (lines.isEmpty() || !lines.getFirst().isGroupStart()) {
            return lines;
        }
        for (int i = 1; i < lines.size(); i++) {
            if ("endgroup".equals(lines.get(i).command())) {
                return lines.subList(i + 1, lines.size());
            }
        }
        return lines;
    }

    boolean hasErrors() {
        return lines.stream().anyMatch(LogLine::isError);
    }

    /** Lines matching {@code pattern}, in order. */
    List<LogLine> matching(Pattern pattern) {
        return lines.stream().filter(line -> pattern.matcher(line.content()).find()).toList();
    }

    Optional<LogLine> first(Pattern pattern) {
        return lines.stream().filter(line -> pattern.matcher(line.content()).find()).findFirst();
    }

    boolean contains(Pattern pattern) {
        return first(pattern).isPresent();
    }

    /** Line with the given log line number, if it belongs to this step. */
    Optional<LogLine> line(int number) {
        int index = number - lines.getFirst().number();
        return index >= 0 && index < lines.size() ? Optional.of(lines.get(index)) : Optional.empty();
    }

    /** The lines after {@code line} up to {@code max}, for reading continuation lines. */
    List<LogLine> after(LogLine line, int max) {
        int index = line.number() - lines.getFirst().number() + 1;
        return index >= lines.size() ? List.of() : lines.subList(index, (int) Math.min(lines.size(), (long) index + max));
    }

    /** The first match of {@code pattern} in any line, for pulling one value out of the step. */
    Optional<Matcher> find(Pattern pattern) {
        for (LogLine line : lines) {
            Matcher matcher = pattern.matcher(line.content());
            if (matcher.find()) {
                return Optional.of(matcher);
            }
        }
        return Optional.empty();
    }
}
