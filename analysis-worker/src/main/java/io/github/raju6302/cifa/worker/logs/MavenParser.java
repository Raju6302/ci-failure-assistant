package io.github.raju6302.cifa.worker.logs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maven builds: compiler errors, Surefire/Failsafe test failures, dependency resolution. */
final class MavenParser implements LogParser {

    private static final Pattern STRONG_SIGNATURE =
            Pattern.compile("^\\[INFO\\] BUILD FAILURE$|^\\[ERROR\\] Failed to execute goal |^\\[INFO\\] Scanning for projects");
    private static final Pattern WEAK_SIGNATURE = Pattern.compile("^\\[(?:INFO|ERROR|WARNING)\\] ");
    private static final Pattern MAVEN_COMMAND = Pattern.compile("\\b(?:mvn|mvnw)\\b");

    // [ERROR] /path/InvoiceService.java:[9,34] cannot find symbol
    private static final Pattern COMPILE_ERROR = Pattern.compile(
            "^\\[ERROR\\] (?<path>(?:file:)?(?:[A-Za-z]:)?[^\\s\\[\\]:]{1,500}\\.(?:java|kt|scala|groovy)):"
                    + "\\[(?<line>\\d{1,7})(?:,(?<column>\\d{1,7}))?\\] (?<message>.{1,500})$");
    // [ERROR] file:///path/Foo.kt:12:5 Unresolved reference: bar   (kotlin-maven-plugin)
    private static final Pattern KOTLIN_ERROR = Pattern.compile(
            "^\\[ERROR\\] (?:file://)?(?<path>[^\\s:]{1,500}\\.kts?):(?<line>\\d{1,7}):(?<column>\\d{1,7}) (?<message>.{1,500})$");
    private static final Pattern SYMBOL = Pattern.compile("^(?:\\[ERROR\\])?\\s{1,16}symbol:\\s{1,16}(?<symbol>.{1,200})$");
    private static final Pattern ERROR_COUNT = Pattern.compile("^\\[INFO\\] \\d{1,6} errors?\\s*$");

    private static final Pattern FAILING_TEST_SET =
            Pattern.compile("Tests run: \\d{1,7}, Failures: (?<failures>\\d{1,7}), Errors: (?<errors>\\d{1,7}).{0,200}<<< (?:FAILURE|ERROR)!");
    // Surefire 3: "[ERROR] shop.FooTest.bar -- Time elapsed: 0.02 s <<< FAILURE!"; Surefire 2: "[ERROR] bar(shop.FooTest)  Time elapsed: …"
    private static final Pattern FAILED_TEST = Pattern.compile(
            "^\\[ERROR\\] (?!Tests run:)(?<name>[\\w$.\\[\\]-]{1,300}?)(?:\\((?<class>[\\w$.]{1,300})\\))?\\s{1,4}(?:-- )?"
                    + "Time elapsed: .{0,40}<<< (?:FAILURE|ERROR)!");
    private static final Pattern RESULTS_HEADER = Pattern.compile("^\\[ERROR\\] (?:Failures|Errors): ?$");
    private static final Pattern RESULT_ENTRY =
            Pattern.compile("^\\[ERROR\\] {2,8}(?<test>[^\\s:]{1,300}?)(?::(?<line>\\d{1,7}))? (?<message>.{1,500})$");
    private static final Pattern TEST_TOTALS = Pattern.compile("^\\[(?:ERROR|WARNING)\\] Tests run: \\d{1,7}, Failures: ");

    // Most specific first; the first one found becomes the summary.
    private static final List<Pattern> DEPENDENCY_ERRORS = List.of(
            Pattern.compile("Could not find artifact \\S{1,300}(?: in \\S{1,100})?"),
            Pattern.compile("\\S{1,300} was not found in \\S{1,300} during a previous attempt"),
            Pattern.compile("Could not transfer artifact \\S{1,300}.{0,300}"),
            Pattern.compile("Failed to read artifact descriptor for \\S{1,300}"),
            Pattern.compile("Non-resolvable (?:parent POM|import POM).{0,300}"),
            Pattern.compile("Plugin \\S{1,300} or one of its dependencies could not be resolved"),
            Pattern.compile("Could not resolve dependencies for project \\S{1,300}"));
    private static final Pattern DEPENDENCY_CONTEXT =
            Pattern.compile("^\\[WARNING\\] The POM for \\S{1,300} is (?:missing|invalid)|Could not resolve dependencies|Could not find artifact");
    private static final Pattern POM = Pattern.compile("^\\[INFO\\] {2,4}from (?<pom>\\S{0,300}pom\\.xml)$");
    private static final Pattern GOAL_FAILED = Pattern.compile(
            "^\\[ERROR\\] Failed to execute goal (?<goal>\\S{1,200}) (?:\\([\\w.-]{1,100}\\) )?on project [\\w.-]{1,200}: (?<message>.{1,500})$");
    private static final Pattern SUREFIRE_REPORTS = Pattern.compile("/target/(?:surefire|failsafe)-reports");

    private static final String ERROR_PREFIX = "[ERROR] ";
    private static final int MAX_TRACE_LINES = 40;

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public int signature(StepLog step) {
        if (step.contains(STRONG_SIGNATURE) || step.name() != null && MAVEN_COMMAND.matcher(step.name()).find()) {
            return 2;
        }
        return step.contains(WEAK_SIGNATURE) ? 1 : 0;
    }

    @Override
    public Optional<Finding> parse(StepLog step) {
        return compileErrors(step)
                .or(() -> testFailures(step))
                .or(() -> dependencyFailure(step))
                .or(() -> goalFailure(step));
    }

    private Optional<Finding> compileErrors(StepLog step) {
        record CompileError(LogLine line, String path, String lineNumber, String message, String symbol) {
        }
        Map<String, CompileError> errors = new LinkedHashMap<>();
        for (LogLine line : step.lines()) {
            Matcher matcher = COMPILE_ERROR.matcher(line.content());
            if (!matcher.find()) {
                matcher = KOTLIN_ERROR.matcher(line.content());
                if (!matcher.find()) {
                    continue;
                }
            }
            String symbol = step.after(line, 2).stream()
                    .map(next -> SYMBOL.matcher(next.content()))
                    .filter(Matcher::find)
                    .map(next -> next.group("symbol").strip())
                    .findFirst().orElse(null);
            CompileError error = new CompileError(line, matcher.group("path"), matcher.group("line"),
                    matcher.group("message").strip(), symbol);
            // Maven prints each error twice: as it compiles, and again in the "Failed to execute goal" message.
            errors.putIfAbsent(error.path() + ":" + error.lineNumber() + ":" + matcher.group("column") + " " + error.message(), error);
        }
        if (errors.isEmpty()) {
            return Optional.empty();
        }
        List<CompileError> distinct = List.copyOf(errors.values());
        CompileError first = distinct.getFirst();
        int last = step.lines().stream()
                .filter(line -> line.number() > first.line().number() && ERROR_COUNT.matcher(line.content()).find())
                .findFirst()
                .map(LogLine::number)
                .orElse(distinct.getLast().line().number() + 2);
        String summary = "%s:%s: %s%s%s".formatted(
                CodeReferences.fileName(first.path()), first.lineNumber(), first.message(),
                first.symbol() == null ? "" : ": " + first.symbol(), Finding.more(distinct.size() - 1, "error"));
        List<String> keyLines = distinct.stream()
                .map(e -> CodeReferences.stripWorkspace(e.path()) + " " + e.message() + " " + Objects.toString(e.symbol(), ""))
                .toList();
        List<CodeReference> references = distinct.stream().map(e -> CodeReferences.of(e.path(), e.lineNumber())).toList();
        return Optional.of(new Finding(FailureCategory.COMPILE_ERROR, summary, first.line().number(), last, keyLines,
                CodeReferences.merge(references)));
    }

    private Optional<Finding> testFailures(StepLog step) {
        List<LogLine> failedTests = step.matching(FAILED_TEST);
        Optional<LogLine> failingSet = step.first(FAILING_TEST_SET);
        if (failedTests.isEmpty() && failingSet.isEmpty()) {
            return Optional.empty();
        }
        LogLine start = failingSet.filter(set -> failedTests.isEmpty() || set.number() < failedTests.getFirst().number())
                .orElseGet(failedTests::getFirst);

        Set<String> testClasses = new LinkedHashSet<>();
        for (LogLine line : failedTests) {
            Matcher matcher = FAILED_TEST.matcher(line.content());
            if (matcher.find()) {
                testClasses.add(matcher.group("class") != null ? matcher.group("class") : withoutLastSegment(matcher.group("name")));
            }
        }

        List<String> results = resultEntries(step);
        String summary;
        List<String> keyLines;
        if (!results.isEmpty()) {
            summary = results.getFirst() + Finding.more(results.size() - 1, "failure");
            keyLines = results;
        } else if (!failedTests.isEmpty()) {
            LogLine first = failedTests.getFirst();
            Matcher matcher = FAILED_TEST.matcher(first.content());
            String test = matcher.find() ? matcher.group("name") : first.content();
            String error = step.after(first, 3).stream().map(LogLine::content).filter(text -> !text.isBlank())
                    .findFirst().orElse("failed");
            summary = test + ": " + JUnitMessages.readable(error.strip()) + Finding.more(failedTests.size() - 1, "failure");
            keyLines = List.of(test, error);
        } else {
            summary = "Tests failed: " + withoutLevel(start.content());
            keyLines = List.of(start.content());
        }

        int last = step.lines().stream()
                .filter(line -> line.number() > start.number() && TEST_TOTALS.matcher(line.content()).find())
                .reduce((a, b) -> b)
                .map(LogLine::number)
                .orElse(start.number() + MAX_TRACE_LINES);
        List<LogLine> traces = step.lines().stream().filter(line -> line.number() >= start.number()).toList();
        String moduleDir = moduleDir(step);
        List<CodeReference> references = CodeReferences.merge(JvmStackFrames.references(traces, moduleDir, testClasses));
        return Optional.of(new Finding(FailureCategory.TEST_FAILURE, summary, start.number(), last, keyLines, references));
    }

    /** "PriceCalculatorTest.applies: expected <90.0> but was <-900.0>" from Surefire's Results section. */
    private static List<String> resultEntries(StepLog step) {
        List<String> entries = new ArrayList<>();
        boolean inResults = false;
        for (LogLine line : step.lines()) {
            if (RESULTS_HEADER.matcher(line.content()).find()) {
                inResults = true;
                continue;
            }
            Matcher entry = RESULT_ENTRY.matcher(line.content());
            if (inResults && entry.find()) {
                // Rerun attempts ("Run 1: …") belong to the entry above them.
                if (!entry.group("test").startsWith("Run")) {
                    String message = entry.group("message").strip();
                    // Errors (as opposed to failures) are listed as "» NullPointer …".
                    message = message.startsWith("» ") ? message.substring(2) : message;
                    entries.add(entry.group("test") + ": " + JUnitMessages.readable(message));
                }
            } else {
                inResults = false;
            }
        }
        return entries;
    }

    private Optional<Finding> dependencyFailure(StepLog step) {
        for (Pattern pattern : DEPENDENCY_ERRORS) {
            Optional<Matcher> found = step.find(pattern);
            if (found.isEmpty()) {
                continue;
            }
            String error = found.get().group();
            List<LogLine> context = step.matching(DEPENDENCY_CONTEXT);
            List<LogLine> errorLines = step.matching(pattern);
            int first = Math.min(context.isEmpty() ? Integer.MAX_VALUE : context.getFirst().number(), errorLines.getFirst().number());
            int last = Math.max(context.isEmpty() ? 0 : context.getLast().number(), errorLines.getLast().number());
            List<CodeReference> poms = step.matching(POM).stream()
                    .map(line -> POM.matcher(line.content()))
                    .filter(Matcher::find)
                    .map(matcher -> CodeReferences.of(matcher.group("pom"), (Integer) null))
                    .toList();
            return Optional.of(new Finding(FailureCategory.DEPENDENCY, error, first, last, List.of(error),
                    CodeReferences.merge(poms)));
        }
        return Optional.empty();
    }

    /** Any other plugin failure (enforcer, checkstyle, …): at least name the goal and its message. */
    private Optional<Finding> goalFailure(StepLog step) {
        return step.first(GOAL_FAILED).map(line -> {
            Matcher matcher = GOAL_FAILED.matcher(line.content());
            String summary = matcher.find() ? matcher.group("goal") + ": " + matcher.group("message") : line.content();
            return new Finding(FailureCategory.UNKNOWN, summary, line.number(), line.number() + 5, List.of(summary), List.of());
        });
    }

    /** The module that failed: Surefire names its report directory, otherwise the first module path seen. */
    private static String moduleDir(StepLog step) {
        List<LogLine> reports = step.matching(SUREFIRE_REPORTS);
        String fromReports = CodeReferences.moduleDir(reports);
        return fromReports != null ? fromReports : CodeReferences.moduleDir(step.lines());
    }


    private static String withoutLevel(String content) {
        return content.startsWith(ERROR_PREFIX) ? content.substring(ERROR_PREFIX.length()) : content;
    }

    private static String withoutLastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
