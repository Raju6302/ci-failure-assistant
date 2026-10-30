package io.github.raju6302.cifa.worker.logs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Node.js: the built-in test runner, Jest, TAP output, npm errors and TypeScript compiler errors. */
final class NodeParser implements LogParser {

    private static final Pattern STRONG_SIGNATURE = Pattern.compile(
            "^> node --test|^ℹ (?:tests|fail) \\d|^✖ failing tests:|^(?:npm ERR!|npm error) |^Test Suites: |^TAP version \\d"
                    + "|\\): error TS\\d{1,5}: ");
    private static final Pattern WEAK_SIGNATURE = Pattern.compile("^✖ |^\\s{0,8}● |^not ok \\d|^\\s{0,8}at .{0,200}\\.[cm]?[jt]sx?:\\d");
    private static final Pattern NODE_COMMAND = Pattern.compile("\\b(?:npm|npx|yarn|pnpm|node|jest|vitest)\\b");

    // TypeScript (tsc without --pretty, as in CI): "src/a.ts(3,7): error TS2322: Type 'string' is not …"
    private static final Pattern TS_ERROR = Pattern.compile(
            "^(?<path>[^\\s(]{1,500}\\.(?:tsx?|mts|cts))\\((?<line>\\d{1,7}),\\d{1,7}\\): error (?<code>TS\\d{1,5}): (?<message>.{1,500})$");

    // node --test: "✖ failing tests:", then per test "test at slugify.test.js:9:1" and "✖ name (1.2ms)"
    private static final Pattern FAILING_TESTS_HEADER = Pattern.compile("^✖ failing tests:$");
    private static final Pattern TEST_LOCATION = Pattern.compile("^test at (?<path>\\S{1,500}?):(?<line>\\d{1,7}):\\d{1,7}$");
    private static final Pattern FAILED_NODE_TEST = Pattern.compile("^✖ (?<name>.{1,300}?) \\(\\d{1,9}(?:\\.\\d{1,9})?m?s\\)$");
    private static final Pattern ACTUAL = Pattern.compile("^\\s{2,16}actual: (?<value>.{1,200}?),?$");
    private static final Pattern EXPECTED = Pattern.compile("^\\s{2,16}expected: (?<value>.{1,200}?),?$");

    // Jest: "  ● math › adds numbers", then "Expected: 3" / "Received: 4"
    private static final Pattern JEST_TEST = Pattern.compile("^\\s{0,8}● (?<name>.{1,300})$");
    private static final Pattern JEST_SUITE_FAILED = Pattern.compile("^Test suite failed to run$");
    private static final Pattern JEST_EXPECTED = Pattern.compile("^\\s{0,16}Expected(?: value)?: {1,8}(?<value>.{1,200})$");
    private static final Pattern JEST_RECEIVED = Pattern.compile("^\\s{0,16}Received(?: value)?: {1,8}(?<value>.{1,200})$");
    private static final Pattern JEST_END = Pattern.compile("^\\s{0,8}● |^Test Suites: ");
    private static final Pattern JEST_FILE = Pattern.compile("^FAIL {1,2}(?<path>\\S{1,500}\\.[cm]?[jt]sx?)\\b");

    // TAP: "not ok 2 - collapses punctuation"
    private static final Pattern TAP_FAILURE = Pattern.compile("^\\s{0,16}not ok \\d{1,6} - (?<name>.{1,300})$");
    private static final Pattern TAP_ERROR = Pattern.compile("^\\s{2,32}(?:error|message): (?:[|>]-?)?\\s{0,4}'?(?<message>.{0,300}?)'?$");

    // Stack frames: "at fn (file:///…/slugify.test.js:10:10)" or "at /…/a.js:3:9"
    private static final Pattern JS_FRAME = Pattern.compile(
            "^\\s{0,16}at (?:.{0,200}? \\()?(?:file://)?(?<path>[^\\s():]{1,500}\\.[cm]?[jt]sx?):(?<line>\\d{1,7}):\\d{1,7}\\)?$");

    private static final Pattern NPM_CODE = Pattern.compile("^(?:npm ERR!|npm error) code (?<code>E[A-Z0-9_]{1,30})$");
    private static final Pattern NPM_MESSAGE = Pattern.compile("^(?:npm ERR!|npm error) (?<message>\\S.{0,500})$");
    private static final Pattern NPM_MISSING_SCRIPT = Pattern.compile("Missing script: \"[^\"]{1,100}\"");
    private static final Pattern NPM_NOISE = Pattern.compile(
            "^(?:code |errno |syscall |path |A complete log|Lifecycle script|Test failed|command (?:failed|sh)|cwd |signal "
                    + "|Exit status|Failed at the|This is probably not a problem|in workspace|location |\\s*$)");
    private static final Set<String> NPM_DEPENDENCY_CODES = Set.of("E404", "ETARGET", "ERESOLVE", "ENOVERSIONS", "EINTEGRITY", "E401", "E403");
    private static final Set<String> NPM_NETWORK_CODES = Set.of("ETIMEDOUT", "ECONNRESET", "ECONNREFUSED", "EAI_AGAIN", "ENOTFOUND");
    private static final Pattern MISSING_PACKAGE = Pattern.compile("Cannot find (?:module|package) '(?<module>[^'./][^']{0,200})'");

    private static final int MAX_DETAIL_LINES = 40;

    @Override
    public String name() {
        return "node";
    }

    @Override
    public int signature(StepLog step) {
        if (step.contains(STRONG_SIGNATURE) || step.name() != null && NODE_COMMAND.matcher(step.name()).find()) {
            return 2;
        }
        return step.contains(WEAK_SIGNATURE) ? 1 : 0;
    }

    @Override
    public Optional<Finding> parse(StepLog step) {
        return typeScriptErrors(step)
                .or(() -> nodeTestFailures(step))
                .or(() -> jestFailures(step))
                .or(() -> tapFailures(step))
                .or(() -> npmError(step));
    }

    private Optional<Finding> typeScriptErrors(StepLog step) {
        List<LogLine> errors = step.matching(TS_ERROR);
        if (errors.isEmpty()) {
            return Optional.empty();
        }
        List<String> keyLines = new ArrayList<>();
        List<CodeReference> references = new ArrayList<>();
        String summary = null;
        for (LogLine line : errors) {
            Matcher matcher = TS_ERROR.matcher(line.content());
            if (matcher.find()) {
                String error = "%s(%s): %s %s".formatted(CodeReferences.fileName(matcher.group("path")), matcher.group("line"),
                        matcher.group("code"), matcher.group("message"));
                summary = summary == null ? error : summary;
                keyLines.add(CodeReferences.stripWorkspace(matcher.group("path")) + " " + matcher.group("code") + " "
                        + matcher.group("message"));
                references.add(CodeReferences.of(matcher.group("path"), matcher.group("line")));
            }
        }
        return Optional.of(new Finding(FailureCategory.COMPILE_ERROR, summary + Finding.more(errors.size() - 1, "error"),
                errors.getFirst().number(), errors.getLast().number(), keyLines, CodeReferences.merge(references)));
    }

    /** The built-in runner repeats every failure, with its details, after "✖ failing tests:". */
    private Optional<Finding> nodeTestFailures(StepLog step) {
        Optional<LogLine> header = step.first(FAILING_TESTS_HEADER);
        List<LogLine> section = header.map(start -> step.after(start, Integer.MAX_VALUE)).orElse(step.lines());
        List<LogLine> failures = section.stream().filter(line -> FAILED_NODE_TEST.matcher(line.content()).find()).toList();
        if (failures.isEmpty()) {
            return Optional.empty();
        }
        LogLine first = failures.getFirst();
        Matcher name = FAILED_NODE_TEST.matcher(first.content());
        String test = name.find() ? name.group("name") : first.content();
        List<LogLine> details = step.after(first, MAX_DETAIL_LINES).stream()
                .takeWhile(line -> !line.isError() && !TEST_LOCATION.matcher(line.content()).find()
                        && !FAILED_NODE_TEST.matcher(line.content()).find())
                .toList();
        String error = details.stream().map(LogLine::content).filter(text -> !text.isBlank()).findFirst()
                .map(String::strip).orElse("failed");
        String actual = value(details, ACTUAL);
        String expected = value(details, EXPECTED);
        String outcome = actual != null && expected != null ? "expected " + expected + " but was " + actual : error;

        List<CodeReference> references = new ArrayList<>();
        int start = first.number();
        Optional<LogLine> location = step.line(first.number() - 1).filter(line -> TEST_LOCATION.matcher(line.content()).find());
        if (location.isPresent()) {
            Matcher matcher = TEST_LOCATION.matcher(location.get().content());
            if (matcher.find()) {
                references.add(CodeReferences.of(matcher.group("path"), matcher.group("line")));
            }
            start = location.get().number();
        }
        references.addAll(0, frames(details));
        int distinctFailures = (int) failures.stream().map(LogLine::content).distinct().count();
        int end = lastNonBlank(details, first);
        return Optional.of(new Finding(testCategory(error), test + ": " + outcome + Finding.more(distinctFailures - 1, "failure"),
                start, end, List.of(test, error), CodeReferences.merge(references)));
    }

    private Optional<Finding> jestFailures(StepLog step) {
        List<LogLine> tests = step.matching(JEST_TEST);
        if (tests.isEmpty()) {
            return Optional.empty();
        }
        LogLine first = tests.getFirst();
        Matcher matcher = JEST_TEST.matcher(first.content());
        String test = matcher.find() ? matcher.group("name").strip() : first.content();
        List<LogLine> details = step.after(first, MAX_DETAIL_LINES).stream()
                .takeWhile(line -> !line.isError() && !JEST_END.matcher(line.content()).find())
                .toList();
        String error = details.stream().map(LogLine::content).filter(text -> !text.isBlank()).findFirst()
                .map(String::strip).orElse("failed");
        String expected = value(details, JEST_EXPECTED);
        String received = value(details, JEST_RECEIVED);
        String outcome = expected != null && received != null ? "expected " + expected + " but received " + received : error;
        FailureCategory category = JEST_SUITE_FAILED.matcher(test).find() ? suiteCategory(details) : testCategory(error);

        List<CodeReference> references = new ArrayList<>(frames(details));
        step.matching(JEST_FILE).forEach(line -> {
            Matcher file = JEST_FILE.matcher(line.content());
            if (file.find()) {
                references.add(CodeReferences.of(file.group("path"), (Integer) null));
            }
        });
        int end = lastNonBlank(details, first);
        // Jest lists every failure again under "Summary of all failing tests".
        int distinctFailures = (int) tests.stream().map(LogLine::content).distinct().count();
        return Optional.of(new Finding(category, test + ": " + outcome + Finding.more(distinctFailures - 1, "failure"),
                first.number(), end, List.of(test, error), CodeReferences.merge(references)));
    }

    private Optional<Finding> tapFailures(StepLog step) {
        List<LogLine> failures = step.matching(TAP_FAILURE);
        if (failures.isEmpty()) {
            return Optional.empty();
        }
        LogLine first = failures.getFirst();
        Matcher matcher = TAP_FAILURE.matcher(first.content());
        String test = matcher.find() ? matcher.group("name").strip() : first.content();
        List<LogLine> details = step.after(first, MAX_DETAIL_LINES).stream()
                .takeWhile(line -> !TAP_FAILURE.matcher(line.content()).find() && !line.content().strip().equals("..."))
                .toList();
        String error = tapError(details);
        int end = lastNonBlank(details, first);
        return Optional.of(new Finding(testCategory(error), test + ": " + error + Finding.more(failures.size() - 1, "failure"),
                first.number(), end, List.of(test, error), CodeReferences.merge(frames(details))));
    }

    /** npm's own failures (registry, install, missing script); a failing test script is handled above. */
    private Optional<Finding> npmError(StepLog step) {
        List<LogLine> npmLines = step.matching(NPM_MESSAGE);
        if (npmLines.isEmpty()) {
            return Optional.empty();
        }
        String code = step.find(NPM_CODE).map(matcher -> matcher.group("code")).orElse(null);
        Optional<LogLine> missingScript = step.first(NPM_MISSING_SCRIPT);
        String message = npmLines.stream()
                .map(line -> NPM_MESSAGE.matcher(line.content()))
                .filter(Matcher::find)
                .map(matcher -> matcher.group("message").strip())
                .filter(text -> !NPM_NOISE.matcher(text).find())
                .findFirst().orElse(null);
        // ELIFECYCLE only says a script such as "npm test" exited non-zero; the script's own output says why.
        if ("ELIFECYCLE".equals(code) || code == null && missingScript.isEmpty() && message == null) {
            return Optional.empty();
        }
        FailureCategory category;
        if (missingScript.isPresent() || "ENOENT".equals(code)) {
            category = FailureCategory.CONFIG_ERROR;
        } else if (code != null && NPM_DEPENDENCY_CODES.contains(code)) {
            category = FailureCategory.DEPENDENCY;
        } else if (code != null && NPM_NETWORK_CODES.contains(code)) {
            category = FailureCategory.INFRASTRUCTURE;
        } else {
            category = FailureCategory.UNKNOWN;
        }
        String detail = message == null ? code : message;
        String summary = code == null ? "npm: " + detail : "npm " + code + ": " + detail;
        return Optional.of(new Finding(category, summary, npmLines.getFirst().number(), npmLines.getLast().number(),
                List.of(code == null ? "" : code, detail), List.of()));
    }

    /** The YAML block's error message, which may start on the line after {@code error: |-}. */
    private static String tapError(List<LogLine> details) {
        for (int i = 0; i < details.size(); i++) {
            Matcher matcher = TAP_ERROR.matcher(details.get(i).content());
            if (!matcher.find()) {
                continue;
            }
            String message = matcher.group("message").strip();
            if (!message.isEmpty()) {
                return message;
            }
            if (i + 1 < details.size() && !details.get(i + 1).isBlank()) {
                return details.get(i + 1).content().strip();
            }
        }
        return "failed";
    }

    private static FailureCategory testCategory(String error) {
        return MISSING_PACKAGE.matcher(error).find() ? FailureCategory.DEPENDENCY : FailureCategory.TEST_FAILURE;
    }

    /** A Jest suite that could not even load: a missing package or a syntax error, not a failing assertion. */
    private static FailureCategory suiteCategory(List<LogLine> details) {
        for (LogLine line : details) {
            if (MISSING_PACKAGE.matcher(line.content()).find()) {
                return FailureCategory.DEPENDENCY;
            }
            if (line.content().contains("SyntaxError")) {
                return FailureCategory.COMPILE_ERROR;
            }
        }
        return FailureCategory.TEST_FAILURE;
    }

    private static List<CodeReference> frames(List<LogLine> lines) {
        List<CodeReference> references = new ArrayList<>();
        for (LogLine line : lines) {
            Matcher frame = JS_FRAME.matcher(line.content());
            if (frame.find()) {
                references.add(CodeReferences.of(frame.group("path"), frame.group("line")));
            }
        }
        return references;
    }

    private static String value(List<LogLine> lines, Pattern pattern) {
        for (LogLine line : lines) {
            Matcher matcher = pattern.matcher(line.content());
            if (matcher.find()) {
                return matcher.group("value").strip();
            }
        }
        return null;
    }

    /** Where a failure's details end, ignoring the blank lines runners print between tests. */
    private static int lastNonBlank(List<LogLine> details, LogLine start) {
        for (int i = details.size() - 1; i >= 0; i--) {
            if (!details.get(i).isBlank()) {
                return details.get(i).number();
            }
        }
        return start.number();
    }
}
