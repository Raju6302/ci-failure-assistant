package io.github.raju6302.cifa.worker.logs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Python: pytest failures and collection errors, pip resolution errors, uncaught tracebacks. */
final class PythonParser implements LogParser {

    private static final Pattern STRONG_SIGNATURE = Pattern.compile(
            "^=+ (?:FAILURES|ERRORS|short test summary info|test session starts) =+$|^(?:FAILED|ERROR) [^\\s:]{1,300}\\.py"
                    + "|^ERROR: (?:Could not find a version|No matching distribution|Could not open requirements file)");
    private static final Pattern WEAK_SIGNATURE = Pattern.compile("^E {3}|^Traceback \\(most recent call last\\):$|^\\d{1,6} failed");
    private static final Pattern PYTHON_COMMAND = Pattern.compile("\\b(?:pytest|python3?|pip3?|tox|nox|poetry|uv)\\b");

    private static final Pattern SECTION = Pattern.compile("^=+ (?:FAILURES|ERRORS) =+$");
    // "FAILED test_durations.py::test_hours_and_minutes - AssertionError: assert 31 == 5400"
    private static final Pattern SUMMARY_ENTRY = Pattern.compile(
            "^(?<kind>FAILED|ERROR) (?<file>[^\\s:]{1,300}\\.py)(?:::(?<test>\\S{1,300}?))?(?: - (?<message>.{1,500}))?$");
    private static final Pattern TOTALS = Pattern.compile("^=*\\s{0,4}\\d{1,6} (?:failed|error|errors)\\b.{0,200}$");
    private static final Pattern TEST_HEADER = Pattern.compile("^_{3,} (?<test>\\S.{0,300}?) _{3,}$");
    private static final Pattern ASSERTION = Pattern.compile("^E {1,8}(?<message>\\S.{0,500})$");
    // "test_durations.py:9: AssertionError" and "File "/…/app.py", line 3, in <module>"
    private static final Pattern LOCATION = Pattern.compile("^(?<file>[^\\s:]{1,300}\\.py):(?<line>\\d{1,7}): \\w{1,100}");
    private static final Pattern TRACEBACK_FRAME = Pattern.compile("^\\s{0,8}File \"(?<file>[^\"]{1,300}\\.py)\", line (?<line>\\d{1,7})");
    private static final Pattern TRACEBACK = Pattern.compile("^Traceback \\(most recent call last\\):$");
    private static final Pattern EXCEPTION = Pattern.compile("^(?<type>[A-Za-z_][\\w.]{0,100}(?:Error|Exception|Exit|Interrupt)): ?(?<message>.{0,300})$");
    private static final Pattern IMPORT_PROBLEM = Pattern.compile("\\b(?:ModuleNotFoundError|ImportError)\\b");
    private static final Pattern SYNTAX_PROBLEM = Pattern.compile("\\b(?:SyntaxError|IndentationError|TabError)\\b");
    private static final Pattern PIP_RESOLUTION = Pattern.compile(
            "^ERROR: (?:Could not find a version that satisfies the requirement \\S{1,200}.{0,200}|No matching distribution found for \\S{1,200}"
                    + "|ResolutionImpossible.{0,200}|Cannot install .{1,300} because these package versions have conflicting dependencies\\.?)$");
    private static final Pattern PIP_MISSING_FILE = Pattern.compile("^ERROR: Could not open requirements file: .{1,300}$");

    @Override
    public String name() {
        return "python";
    }

    @Override
    public int signature(StepLog step) {
        if (step.contains(STRONG_SIGNATURE) || step.name() != null && PYTHON_COMMAND.matcher(step.name()).find()) {
            return 2;
        }
        return step.contains(WEAK_SIGNATURE) ? 1 : 0;
    }

    @Override
    public Optional<Finding> parse(StepLog step) {
        return pipError(step)
                .or(() -> pytestSummary(step))
                .or(() -> pytestSections(step))
                .or(() -> traceback(step));
    }

    private Optional<Finding> pipError(StepLog step) {
        Optional<LogLine> missingFile = step.first(PIP_MISSING_FILE);
        if (missingFile.isPresent()) {
            return Optional.of(single(FailureCategory.CONFIG_ERROR, missingFile.get()));
        }
        return step.first(PIP_RESOLUTION).map(line -> single(FailureCategory.DEPENDENCY, line));
    }

    /** pytest's "short test summary info": one line per failed test, with the error. */
    private Optional<Finding> pytestSummary(StepLog step) {
        List<LogLine> entries = step.matching(SUMMARY_ENTRY);
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        List<String> keyLines = new ArrayList<>();
        List<CodeReference> references = new ArrayList<>();
        String summary = null;
        FailureCategory category = null;
        for (LogLine entry : entries) {
            Matcher matcher = SUMMARY_ENTRY.matcher(entry.content());
            if (!matcher.find()) {
                continue;
            }
            String message = matcher.group("message") != null ? matcher.group("message") : firstAssertion(step).orElse("");
            String test = matcher.group("test") == null ? matcher.group("file") : matcher.group("file") + "::" + matcher.group("test");
            if (summary == null) {
                summary = message.isEmpty() ? test + " " + matcher.group("kind").toLowerCase() : test + ": " + message;
                category = category(message);
            }
            keyLines.add(matcher.group("kind") + " " + test + " " + message);
            references.add(CodeReferences.of(matcher.group("file"), (Integer) null));
        }
        int start = step.first(SECTION).map(LogLine::number).orElse(entries.getFirst().number());
        int end = step.lines().stream().filter(line -> line.number() > entries.getLast().number())
                .filter(line -> TOTALS.matcher(line.content()).find())
                .findFirst().map(LogLine::number).orElse(entries.getLast().number());
        references.addAll(0, locations(step));
        return Optional.of(new Finding(category, summary + Finding.more(entries.size() - 1, "failure"), start, end, keyLines,
                CodeReferences.merge(references)));
    }

    /** Without a summary (e.g. {@code -rN}): the first "____ test_name ____" section and its "E " line. */
    private Optional<Finding> pytestSections(StepLog step) {
        return step.first(TEST_HEADER).flatMap(header -> firstAssertion(step).map(message -> {
            Matcher matcher = TEST_HEADER.matcher(header.content());
            String test = matcher.find() ? matcher.group("test") : header.content();
            return new Finding(category(message), test + ": " + message, header.number(), header.number() + 20,
                    List.of(test, message), CodeReferences.merge(locations(step)));
        }));
    }

    /** A script that crashed: the exception at the end of the traceback. */
    private Optional<Finding> traceback(StepLog step) {
        return step.first(TRACEBACK).flatMap(start -> step.after(start, 60).stream()
                .filter(line -> EXCEPTION.matcher(line.content()).find())
                .findFirst()
                .map(exception -> {
                    String message = exception.content().strip();
                    List<LogLine> frames = step.lines().stream()
                            .filter(line -> line.number() > start.number() && line.number() < exception.number())
                            .toList();
                    FailureCategory category = IMPORT_PROBLEM.matcher(message).find() ? FailureCategory.DEPENDENCY
                            : SYNTAX_PROBLEM.matcher(message).find() ? FailureCategory.COMPILE_ERROR
                            : FailureCategory.UNKNOWN;
                    return new Finding(category, message, start.number(), exception.number(), List.of(message),
                            CodeReferences.merge(tracebackFrames(frames)));
                }));
    }

    private static FailureCategory category(String message) {
        if (IMPORT_PROBLEM.matcher(message).find()) {
            return FailureCategory.DEPENDENCY;
        }
        if (SYNTAX_PROBLEM.matcher(message).find()) {
            return FailureCategory.COMPILE_ERROR;
        }
        return FailureCategory.TEST_FAILURE;
    }

    private static Optional<String> firstAssertion(StepLog step) {
        return step.find(ASSERTION).map(matcher -> matcher.group("message").strip());
    }

    private static List<CodeReference> locations(StepLog step) {
        List<CodeReference> references = new ArrayList<>();
        for (LogLine line : step.lines()) {
            Matcher matcher = LOCATION.matcher(line.content());
            if (matcher.find()) {
                references.add(CodeReferences.of(matcher.group("file"), matcher.group("line")));
            }
        }
        references.addAll(tracebackFrames(step.lines()));
        return references;
    }

    private static List<CodeReference> tracebackFrames(List<LogLine> lines) {
        List<CodeReference> references = new ArrayList<>();
        for (LogLine line : lines) {
            Matcher matcher = TRACEBACK_FRAME.matcher(line.content());
            if (matcher.find()) {
                references.add(CodeReferences.of(matcher.group("file"), matcher.group("line")));
            }
        }
        return references;
    }

    private static Finding single(FailureCategory category, LogLine line) {
        String error = line.content().strip();
        return new Finding(category, error, line.number(), line.number(), List.of(error), List.of());
    }
}
