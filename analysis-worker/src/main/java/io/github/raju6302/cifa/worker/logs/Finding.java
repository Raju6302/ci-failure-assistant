package io.github.raju6302.cifa.worker.logs;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What a parser found in the failing step.
 *
 * @param firstLine      log line number where the relevant lines start
 * @param lastLine       log line number where they end
 * @param keyLines       the lines that identify this failure, used for the fingerprint
 * @param specific       false when the parser found no actual error message and fell back to
 *                       the step's last lines; a failure annotation may then say more
 */
record Finding(
        FailureCategory category,
        String summary,
        int firstLine,
        int lastLine,
        List<String> keyLines,
        List<CodeReference> codeReferences,
        boolean specific) {

    static final int MAX_SUMMARY_LENGTH = 300;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    Finding {
        summary = oneLine(summary);
        keyLines = List.copyOf(keyLines);
        codeReferences = List.copyOf(codeReferences);
    }

    Finding(FailureCategory category, String summary, int firstLine, int lastLine, List<String> keyLines,
            List<CodeReference> codeReferences) {
        this(category, summary, firstLine, lastLine, keyLines, codeReferences, true);
    }

    static String oneLine(String text) {
        String line = text == null ? "" : WHITESPACE.matcher(text).replaceAll(" ").strip();
        return line.length() > MAX_SUMMARY_LENGTH ? line.substring(0, MAX_SUMMARY_LENGTH - 1) + "…" : line;
    }

    /** "1 more failure", "3 more failures"; empty when there are none. */
    static String more(int count, String noun) {
        return count <= 0 ? "" : " (and %d more %s%s)".formatted(count, noun, count == 1 ? "" : "s");
    }
}
