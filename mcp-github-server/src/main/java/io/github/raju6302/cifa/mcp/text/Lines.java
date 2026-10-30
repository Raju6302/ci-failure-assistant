package io.github.raju6302.cifa.mcp.text;

import java.util.List;

/**
 * Text split into lines, numbered from 1 like editors and compiler errors do, so the agent
 * can cite {@code path:line} and ask for a precise range later.
 */
public final class Lines {

    private final List<String> lines;

    private Lines(List<String> lines) {
        this.lines = lines;
    }

    /** Splits on \n (dropping \r), without an empty last line for a trailing newline. */
    public static Lines of(String text) {
        if (text == null || text.isEmpty()) {
            return new Lines(List.of());
        }
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return new Lines(body.lines().toList());
    }

    public int count() {
        return lines.size();
    }

    public List<String> all() {
        return lines;
    }

    /** @param number 1-based */
    public String get(int number) {
        return lines.get(number - 1);
    }

    /** {@code "  42 | text"}, padded so numbers line up across the whole text. */
    public String numbered(int number) {
        return numbered(number, '|');
    }

    /** Like {@link #numbered(int)} with a different separator, e.g. to mark grep matches. */
    public String numbered(int number, char separator) {
        int width = Integer.toString(Math.max(count(), 1)).length();
        return ("%" + width + "d %c %s").formatted(number, separator, get(number));
    }

    /**
     * Appends lines {@code from..to} (inclusive, 1-based), numbered, as far as they fit.
     *
     * @return the last line number that was added, or {@code from - 1} if none fit
     */
    public int appendNumbered(LimitedText out, int from, int to) {
        for (int number = from; number <= to; number++) {
            if (!out.line(numbered(number))) {
                return number - 1;
            }
        }
        return to;
    }

    /**
     * The first line of the longest tail ({@code from..count()}) that fits in {@code chars}
     * once numbered. Logs keep their errors at the end, so that is the part worth showing.
     */
    public int tailStart(int chars) {
        int used = 0;
        for (int number = count(); number >= 1; number--) {
            used += LimitedText.shorten(numbered(number)).length() + 1;
            if (used > chars) {
                return number + 1;
            }
        }
        return 1;
    }
}
