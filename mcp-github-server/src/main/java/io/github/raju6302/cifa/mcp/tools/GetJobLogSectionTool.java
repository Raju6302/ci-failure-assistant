package io.github.raju6302.cifa.mcp.tools;

import io.github.raju6302.cifa.mcp.McpProperties;
import io.github.raju6302.cifa.mcp.text.LimitedText;
import io.github.raju6302.cifa.mcp.text.Lines;
import io.github.raju6302.cifa.mcp.text.LogGrep;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

@Component
class GetJobLogSectionTool implements AgentTool {

    static final int DEFAULT_CONTEXT = 3;
    static final int MAX_CONTEXT = 20;
    static final int MAX_PATTERN_LENGTH = 200;
    private static final Duration GREP_TIMEOUT = Duration.ofSeconds(2);
    /** Room kept for the "Showing lines ..." line written above a tail. */
    private static final int TAIL_INTRO_CHARS = 200;

    private final JobLogStore logs;
    private final int maxOutputChars;

    GetJobLogSectionTool(JobLogStore logs, McpProperties properties) {
        this.logs = logs;
        this.maxOutputChars = properties.maxOutputChars();
    }

    @Override
    public McpSchema.Tool definition() {
        return AgentTool.readOnly("get_job_log_section", "Read a failed job's log", """
                        Read part of a failed job's log, with numbered lines. Secrets were removed before the log was \
                        stored and appear as [REDACTED]. Three ways to call it: with grep, to find lines matching a \
                        regular expression (case-insensitive) with context_lines of context around each, e.g. \
                        'ERROR|FAIL|Exception'; with start_line/end_line, to read a range (for example around a grep \
                        hit); or with neither, to get the end of the log, where the error usually is. Very long logs \
                        were stored without their beginning.""",
                InputSchema.create()
                        .integer("github_job_id", true, 1, null,
                                "GitHub's id of the failed job (the number in .../job/<github_job_id>).")
                        .integer("start_line", false, 1, null, "First line to return or search (1-based).")
                        .integer("end_line", false, 1, null, "Last line to return or search (inclusive).")
                        .string("grep", false, "Regular expression (Java syntax, case-insensitive); returns only "
                                + "matching lines with context. At most %d characters.".formatted(MAX_PATTERN_LENGTH))
                        .integer("context_lines", false, 0, (long) MAX_CONTEXT, "Lines of context before and after "
                                + "each grep match. Default %d.".formatted(DEFAULT_CONTEXT))
                        .build());
    }

    @Override
    public String call(ToolArguments arguments) {
        long jobId = arguments.requiredLong("github_job_id");
        StoredJobLog job = logs.find(jobId).orElseThrow(() -> new ToolException(("No failed job with github_job_id %d "
                + "is stored. Only failed jobs of runs CI Failure Assistant received can be read.").formatted(jobId)));
        if (job.logText() == null) {
            throw new ToolException("The log of job %d is not available: GitHub had already deleted it.".formatted(jobId));
        }
        Lines lines = Lines.of(job.logText());
        LimitedText out = new LimitedText(maxOutputChars);
        out.line(header(job, lines.count()));
        if (lines.count() == 0) {
            out.line("(the log is empty)");
            return out.finish("");
        }

        Integer start = arguments.optionalInt("start_line");
        Integer end = arguments.optionalInt("end_line");
        int from = start == null ? 1 : start;
        int to = end == null ? lines.count() : Math.min(end, lines.count());
        if (from > lines.count()) {
            throw new ToolException("start_line %d is past the end of the log, which has %d lines.".formatted(from, lines.count()));
        }
        if (to < from) {
            throw new ToolException("end_line must not be before start_line.");
        }

        String grep = arguments.optionalString("grep");
        if (grep != null) {
            Integer context = arguments.optionalInt("context_lines");
            return grep(out, lines, compile(grep), context == null ? DEFAULT_CONTEXT : Math.clamp(context, 0, MAX_CONTEXT),
                    from, to);
        }
        if (start == null && end == null) {
            from = lines.tailStart(out.remaining() - TAIL_INTRO_CHARS);
            out.line("Showing lines %d-%d, the end of the log. For earlier lines pass start_line/end_line, or use grep."
                    .formatted(from, to));
        }
        int last = lines.appendNumbered(out, from, to);
        return out.finish("Call again with start_line=%d to read on, or use grep.".formatted(last + 1));
    }

    private static String grep(LimitedText out, Lines lines, Pattern pattern, int context, int from, int to) {
        List<Integer> matches = LogGrep.matches(lines, pattern, from, to, GREP_TIMEOUT);
        if (matches.isEmpty()) {
            out.line("No line between %d and %d matches /%s/.".formatted(from, to, pattern.pattern()));
            return out.finish("");
        }
        out.line("%d line(s) match /%s/. Matches are marked with '>', context lines with '|'."
                .formatted(matches.size(), pattern.pattern()));
        Set<Integer> matched = new HashSet<>(matches);
        int lastShown = from - 1;
        for (LogGrep.Range range : LogGrep.withContext(matches, context, from, to)) {
            if (lastShown >= from && !out.line("--")) {
                break;
            }
            for (int number = range.from(); number <= range.to(); number++) {
                if (!out.line(lines.numbered(number, matched.contains(number) ? '>' : '|'))) {
                    break;
                }
                lastShown = number;
            }
        }
        return out.finish(("Narrow the pattern, lower context_lines, or search from start_line=%d onwards.")
                .formatted(lastShown + 1));
    }

    private static Pattern compile(String grep) {
        if (grep.length() > MAX_PATTERN_LENGTH) {
            throw new ToolException("The grep pattern is too long; keep it under %d characters.".formatted(MAX_PATTERN_LENGTH));
        }
        try {
            return Pattern.compile(grep, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            throw new ToolException("grep is not a valid regular expression: " + e.getDescription());
        }
    }

    private static String header(StoredJobLog job, int lineCount) {
        String steps = job.failedSteps().isEmpty() ? "" : "; failed steps: " + String.join(", ", job.failedSteps());
        String header = "Log of job %d \"%s\" (%s%s), run %d in %s: %d lines.".formatted(job.githubJobId(), job.name(),
                job.conclusion(), steps, job.githubRunId(), job.repository(), lineCount);
        return job.truncated() ? header + " Only the end of this log was kept, so line 1 is not where the job started."
                : header;
    }
}
