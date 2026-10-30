package io.github.raju6302.cifa.mcp.text;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * grep for logs: which lines match, and which ranges to show around them.
 *
 * <p>The pattern comes from the model, and the log it runs on was written by whatever the
 * build printed, so a pattern with catastrophic backtracking must not hang the server:
 * matching gives up after a time limit.
 */
public final class LogGrep {

    /** Inclusive, 1-based line range. */
    public record Range(int from, int to) {
    }

    private LogGrep() {
    }

    /**
     * Numbers of the lines in {@code from..to} that contain a match.
     *
     * @throws IllegalArgumentException if matching takes longer than {@code timeout}
     */
    public static List<Integer> matches(Lines lines, Pattern pattern, int from, int to, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Integer> matches = new ArrayList<>();
        try {
            for (int number = from; number <= to; number++) {
                if (pattern.matcher(new DeadlineCharSequence(lines.get(number), deadline)).find()) {
                    matches.add(number);
                }
            }
        } catch (StackOverflowError e) {
            // java.util.regex recurses per repetition; nested groups on a long line can exhaust the stack.
            throw new IllegalArgumentException("The grep pattern is too complex for this log; use a simpler pattern");
        }
        return matches;
    }

    /** Each match widened by {@code context} lines on both sides, overlapping ranges merged. */
    public static List<Range> withContext(List<Integer> matches, int context, int from, int to) {
        List<Range> ranges = new ArrayList<>();
        for (int match : matches) {
            int start = Math.max(from, match - context);
            int end = Math.min(to, match + context);
            if (!ranges.isEmpty() && start <= ranges.getLast().to() + 1) {
                Range last = ranges.removeLast();
                ranges.add(new Range(last.from(), Math.max(last.to(), end)));
            } else {
                ranges.add(new Range(start, end));
            }
        }
        return ranges;
    }

    /** Fails the match once the deadline passes; regex engines read their input only through charAt. */
    private record DeadlineCharSequence(CharSequence text, long deadline) implements CharSequence {

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadline) {
                throw new IllegalArgumentException("The grep pattern took too long to run; use a simpler pattern");
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
