package io.github.raju6302.cifa.worker.logs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gradle builds (basic): compiler errors, failed tests, unresolved dependencies, "What went wrong". */
final class GradleParser implements LogParser {

    private static final Pattern STRONG_SIGNATURE =
            Pattern.compile("^FAILURE: Build failed with an exception\\.|^BUILD FAILED in |^\\* What went wrong:");
    private static final Pattern WEAK_SIGNATURE = Pattern.compile("^> Task :");
    private static final Pattern GRADLE_COMMAND = Pattern.compile("\\b(?:gradle|gradlew)\\b");

    // javac through Gradle: "/path/Foo.java:12: error: cannot find symbol"
    private static final Pattern JAVAC_ERROR =
            Pattern.compile("^(?<path>[^\\s:]{1,500}\\.java):(?<line>\\d{1,7}): error: (?<message>.{1,500})$");
    // kotlinc: "e: file:///path/Foo.kt:12:5 Unresolved reference: bar"
    private static final Pattern KOTLIN_ERROR =
            Pattern.compile("^e: (?:file://)?(?<path>[^\\s:]{1,500}\\.kts?):(?<line>\\d{1,7}):\\d{1,7} (?<message>.{1,500})$");
    private static final Pattern SYMBOL = Pattern.compile("^\\s{1,16}symbol:\\s{1,16}(?<symbol>.{1,200})$");
    // "PriceCalculatorTest > appliesPercentageDiscount() FAILED"
    private static final Pattern FAILED_TEST = Pattern.compile("^(?<class>[\\w$.]{1,300}) > (?<test>.{1,300}) FAILED$");
    // Short exception format: "    org.opentest4j.AssertionFailedError at PriceCalculatorTest.java:13"
    private static final Pattern SHORT_LOCATION = Pattern.compile(" at (?<file>[\\w$-]{1,200}\\.(?:java|kt|groovy)):(?<line>\\d{1,7})$");
    private static final Pattern FAILED_TASK = Pattern.compile("^> Task (?<task>:[\\w:-]{0,200}) FAILED$");
    private static final List<Pattern> DEPENDENCY_ERRORS = List.of(
            Pattern.compile("Could not find [\\w.-]{1,200}:[\\w.-]{1,200}(?::[\\w.+-]{1,100})?\\."),
            Pattern.compile("Could not resolve [\\w.-]{1,200}:[\\w.-]{1,200}:[\\w.+-]{1,100}\\."),
            Pattern.compile("Could not resolve all (?:files|dependencies|artifacts) for configuration '[^']{1,200}'\\."));
    private static final Pattern WHAT_WENT_WRONG = Pattern.compile("^\\* What went wrong:$");

    @Override
    public String name() {
        return "gradle";
    }

    @Override
    public int signature(StepLog step) {
        if (step.contains(STRONG_SIGNATURE) || step.name() != null && GRADLE_COMMAND.matcher(step.name()).find()) {
            return 2;
        }
        return step.contains(WEAK_SIGNATURE) ? 1 : 0;
    }

    @Override
    public Optional<Finding> parse(StepLog step) {
        return compileErrors(step)
                .or(() -> testFailures(step))
                .or(() -> dependencyFailure(step))
                .or(() -> whatWentWrong(step));
    }

    private Optional<Finding> compileErrors(StepLog step) {
        List<String> keyLines = new ArrayList<>();
        List<CodeReference> references = new ArrayList<>();
        LogLine first = null;
        LogLine last = null;
        String summary = null;
        for (LogLine line : step.lines()) {
            Matcher matcher = JAVAC_ERROR.matcher(line.content());
            if (!matcher.find()) {
                matcher = KOTLIN_ERROR.matcher(line.content());
                if (!matcher.find()) {
                    continue;
                }
            }
            String message = matcher.group("message").strip();
            if (first == null) {
                first = line;
                String symbol = step.after(line, 3).stream().map(next -> SYMBOL.matcher(next.content()))
                        .filter(Matcher::find).map(next -> ": " + next.group("symbol").strip()).findFirst().orElse("");
                summary = "%s:%s: %s%s".formatted(
                        CodeReferences.fileName(matcher.group("path")), matcher.group("line"), message, symbol);
            }
            last = line;
            keyLines.add(CodeReferences.stripWorkspace(matcher.group("path")) + " " + message);
            references.add(CodeReferences.of(matcher.group("path"), matcher.group("line")));
        }
        if (first == null) {
            return Optional.empty();
        }
        return Optional.of(new Finding(FailureCategory.COMPILE_ERROR, summary + Finding.more(keyLines.size() - 1, "error"),
                first.number(), last.number() + 3, keyLines, CodeReferences.merge(references)));
    }

    private Optional<Finding> testFailures(StepLog step) {
        List<LogLine> failed = step.matching(FAILED_TEST);
        if (failed.isEmpty()) {
            return Optional.empty();
        }
        String moduleDir = failedTaskModule(step);
        List<String> keyLines = new ArrayList<>();
        List<CodeReference> references = new ArrayList<>();
        String summary = null;
        for (LogLine line : failed) {
            Matcher test = FAILED_TEST.matcher(line.content());
            if (!test.find()) {
                continue;
            }
            List<LogLine> details = step.after(line, 30);
            String error = details.stream().map(LogLine::content).filter(text -> !text.isBlank()).findFirst()
                    .map(String::strip).orElse("failed");
            String name = test.group("class") + " > " + test.group("test");
            if (summary == null) {
                summary = name + ": " + JUnitMessages.readable(error);
            }
            keyLines.add(name + " " + error);
            Matcher location = SHORT_LOCATION.matcher(error);
            if (location.find()) {
                references.add(new CodeReference(
                        sourcePath(moduleDir, test.group("class"), location.group("file")), Integer.valueOf(location.group("line"))));
            }
            references.addAll(JvmStackFrames.references(details, moduleDir, Set.of(test.group("class"))));
        }
        LogLine first = failed.getFirst();
        return Optional.of(new Finding(FailureCategory.TEST_FAILURE, summary + Finding.more(failed.size() - 1, "failure"),
                first.number(), failed.getLast().number() + 4, keyLines, CodeReferences.merge(references)));
    }

    private Optional<Finding> dependencyFailure(StepLog step) {
        for (Pattern pattern : DEPENDENCY_ERRORS) {
            Optional<LogLine> line = step.first(pattern);
            if (line.isPresent()) {
                Matcher matcher = pattern.matcher(line.get().content());
                String error = matcher.find() ? matcher.group() : line.get().content();
                return Optional.of(new Finding(FailureCategory.DEPENDENCY, error, line.get().number(), line.get().number() + 4,
                        List.of(error), List.of()));
            }
        }
        return Optional.empty();
    }

    private Optional<Finding> whatWentWrong(StepLog step) {
        return step.first(WHAT_WENT_WRONG).flatMap(header -> {
            List<String> reason = step.after(header, 3).stream().map(LogLine::content).map(String::strip)
                    .takeWhile(text -> !text.isEmpty() && !text.startsWith("* ")).toList();
            if (reason.isEmpty()) {
                return Optional.empty();
            }
            String summary = String.join(" ", reason);
            return Optional.of(new Finding(FailureCategory.UNKNOWN, summary, header.number(), header.number() + reason.size(),
                    List.of(summary), List.of()));
        });
    }

    /** {@code > Task :app:test FAILED} → {@code app}; the root project's tasks → empty. */
    private static String failedTaskModule(StepLog step) {
        return step.find(FAILED_TASK).map(matcher -> {
            String task = matcher.group("task");
            int lastColon = task.lastIndexOf(':');
            return lastColon <= 0 ? "" : task.substring(1, lastColon).replace(':', '/');
        }).orElse("");
    }

    /** Gradle prints the class by simple or full name; either way it is a test class. */
    private static String sourcePath(String moduleDir, String className, String file) {
        int lastDot = className.lastIndexOf('.');
        String packagePath = lastDot < 0 ? "" : className.substring(0, lastDot).replace('.', '/') + "/";
        String language = file.endsWith(".kt") ? "kotlin" : file.endsWith(".groovy") ? "groovy" : "java";
        return "%ssrc/test/%s/%s%s".formatted(moduleDir.isEmpty() ? "" : moduleDir + "/", language, packagePath, file);
    }
}
