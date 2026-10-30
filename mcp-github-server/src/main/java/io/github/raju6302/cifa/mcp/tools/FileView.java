package io.github.raju6302.cifa.mcp.tools;

import io.github.raju6302.cifa.mcp.text.LimitedText;
import io.github.raju6302.cifa.mcp.text.Lines;
import java.util.function.IntFunction;

/** A text file shown as numbered lines, from an optional line range, within the output limit. */
final class FileView {

    private FileView() {
    }

    /**
     * @param readMoreHint given the first line that didn't fit, tells the agent how to read on
     */
    static String render(String header, Lines lines, Integer startLine, Integer endLine, int maxChars,
            IntFunction<String> readMoreHint) {
        int total = lines.count();
        LimitedText out = new LimitedText(maxChars);
        if (total == 0) {
            out.line(header + " (empty file)");
            return out.finish("");
        }
        int from = startLine == null ? 1 : startLine;
        int to = endLine == null ? total : Math.min(endLine, total);
        if (from > total) {
            throw new ToolException("start_line %d is past the end of the file, which has %d lines.".formatted(from, total));
        }
        if (to < from) {
            throw new ToolException("end_line must not be before start_line.");
        }
        out.line("%s (lines %d-%d of %d)".formatted(header, from, to, total));
        int last = lines.appendNumbered(out, from, to);
        return out.finish(readMoreHint.apply(last + 1));
    }
}
