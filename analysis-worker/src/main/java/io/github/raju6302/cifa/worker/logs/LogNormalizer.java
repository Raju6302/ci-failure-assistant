package io.github.raju6302.cifa.worker.logs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw GitHub Actions log into clean lines that keep their original numbers.
 *
 * <p>Logs are untrusted input. Every line is cut to {@value #MAX_LINE_LENGTH} characters
 * before any parser sees it, which together with bounded quantifiers keeps regex matching
 * linear even for a minified bundle or a 2 MB line of garbage.
 */
final class LogNormalizer {

    static final int MAX_LINE_LENGTH = 2_000;
    private static final char BOM = '\uFEFF';

    // 2026-10-30T13:29:50.5453002Z at the start of every line GitHub writes
    private static final Pattern TIMESTAMP = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z ?");
    // CSI sequences (colors, cursor moves), OSC sequences (titles, hyperlinks) and lone escapes
    private static final Pattern ANSI = Pattern.compile(
            "\\x1B\\[[0-9;?]{0,32}[ -/]{0,4}[@-~]|\\x1B\\][^\\x07\\x1B]{0,512}(?:\\x07|\\x1B\\\\)|\\x1B[@-_]");
    private static final Pattern COMMAND = Pattern.compile("^##\\[([a-z]{1,16})\\]");

    private LogNormalizer() {
    }

    static List<LogLine> normalize(String log) {
        if (log == null || log.isEmpty()) {
            return List.of();
        }
        String[] raw = log.split("\n", -1);
        int count = raw.length > 0 && raw[raw.length - 1].isEmpty() ? raw.length - 1 : raw.length;
        List<LogLine> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(line(i + 1, raw[i]));
        }
        return lines;
    }

    private static LogLine line(int number, String raw) {
        // Cut early (escape codes can only shorten the text) so a huge line costs little.
        String text = raw.length() > MAX_LINE_LENGTH * 4 ? raw.substring(0, MAX_LINE_LENGTH * 4) : raw;
        if (!text.isEmpty() && text.charAt(0) == BOM) {
            text = text.substring(1);
        }
        if (text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        text = TIMESTAMP.matcher(text).replaceFirst("");
        if (text.indexOf('\u001B') >= 0) {
            text = ANSI.matcher(text).replaceAll("");
        }
        String command = null;
        Matcher matcher = COMMAND.matcher(text);
        if (matcher.find()) {
            command = matcher.group(1);
            text = text.substring(matcher.end());
        }
        if (text.length() > MAX_LINE_LENGTH) {
            text = text.substring(0, MAX_LINE_LENGTH) + "…";
        }
        return new LogLine(number, text, command);
    }
}
