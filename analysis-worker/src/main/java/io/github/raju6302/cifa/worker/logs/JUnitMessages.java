package io.github.raju6302.cifa.worker.logs;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shortens JUnit assertion messages for summaries. */
final class JUnitMessages {

    private static final Pattern EXPECTED_BUT_WAS =
            Pattern.compile("expected: ?<(?<expected>.{0,200}?)> but was: ?<(?<actual>.{0,200}?)>");

    private JUnitMessages() {
    }

    /** {@code expected: <90.0> but was: <-900.0>} reads better as {@code expected <90.0> but was <-900.0>}. */
    static String readable(String message) {
        Matcher matcher = EXPECTED_BUT_WAS.matcher(message);
        if (!matcher.find()) {
            return message;
        }
        return message.substring(0, matcher.start())
                + "expected <" + matcher.group("expected") + "> but was <" + matcher.group("actual") + ">"
                + message.substring(matcher.end());
    }
}
