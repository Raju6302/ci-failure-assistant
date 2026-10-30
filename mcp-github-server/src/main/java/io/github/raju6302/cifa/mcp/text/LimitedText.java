package io.github.raju6302.cifa.mcp.text;

/**
 * Tool output with a character budget. Text is only cut at line boundaries, so the agent
 * never sees half a line, and the result always says when something was left out.
 */
public final class LimitedText {

    /** Longer lines (minified code, progress bars in logs) are shortened rather than dropping the rest. */
    public static final int MAX_LINE_CHARS = 1000;
    /** Kept free for the truncation notice, so the whole result stays within the budget. */
    static final int NOTICE_RESERVE = 400;

    private final StringBuilder text = new StringBuilder();
    private final int budget;
    private final int maxChars;
    private boolean truncated;

    public LimitedText(int maxChars) {
        this.maxChars = maxChars;
        this.budget = Math.max(maxChars - NOTICE_RESERVE, 0);
    }

    /**
     * Adds one line if it fits.
     *
     * @return false once the budget is used up; nothing more is added after that
     */
    public boolean line(String line) {
        String shortened = shorten(line);
        if (truncated || text.length() + shortened.length() + 1 > budget) {
            truncated = true;
            return false;
        }
        text.append(shortened).append('\n');
        return true;
    }

    /** Adds every line of a multi-line string; stops at the first that doesn't fit. */
    public boolean lines(String multiLine) {
        for (String line : Lines.of(multiLine).all()) {
            if (!line(line)) {
                return false;
            }
        }
        return true;
    }

    /** How many more characters (including line breaks) fit. */
    public int remaining() {
        return truncated ? 0 : budget - text.length();
    }

    public boolean truncated() {
        return truncated;
    }

    /**
     * @param howToGetMore tells the agent how to read what was cut, e.g. which arguments to pass
     */
    public String finish(String howToGetMore) {
        if (!truncated) {
            return text.toString();
        }
        String notice = "[Truncated: limited to %d characters. %s]".formatted(maxChars, howToGetMore);
        return text + (notice.length() <= NOTICE_RESERVE ? notice : notice.substring(0, NOTICE_RESERVE - 2) + "…]");
    }

    static String shorten(String line) {
        if (line.length() <= MAX_LINE_CHARS) {
            return line;
        }
        return line.substring(0, MAX_LINE_CHARS) + " … [line cut, %d more characters]".formatted(line.length() - MAX_LINE_CHARS);
    }
}
