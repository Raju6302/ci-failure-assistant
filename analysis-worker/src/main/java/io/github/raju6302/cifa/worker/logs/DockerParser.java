package io.github.raju6302.cifa.worker.logs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code docker build} with BuildKit, plus the classic builder's messages. */
final class DockerParser implements LogParser {

    private static final Pattern STRONG_SIGNATURE =
            Pattern.compile("^ERROR: failed to (?:solve|build)|^#\\d{1,4} \\[(?:internal|auth|\\d{1,3}/\\d{1,3})\\]|^Sending build context to Docker daemon");
    private static final Pattern WEAK_SIGNATURE = Pattern.compile("^#\\d{1,4} (?:DONE|CACHED|ERROR)|^Dockerfile[\\w.-]{0,50}:\\d{1,6}$");
    private static final Pattern DOCKER_COMMAND = Pattern.compile("\\bdocker(?: buildx)? (?:build|compose)\\b");

    private static final Pattern STEP_ERROR = Pattern.compile("^#\\d{1,4} ERROR: (?<message>.{1,1000})$");
    private static final Pattern FINAL_ERROR =
            Pattern.compile("^ERROR: (?:failed to build: )?failed to solve(?: with frontend [\\w.-]{1,50})?: (?<message>.{1,1000})$");
    private static final Pattern CLASSIC_ERROR = Pattern.compile(
            "^(?<message>(?:COPY|ADD) failed: .{1,500}|The command '.{1,300}' returned a non-zero code: \\d{1,5}"
                    + "|failed to (?:compute cache key|read dockerfile): .{1,500})$");
    // " > [3/5] COPY requirements.txt .:" names the instruction that failed
    private static final Pattern FAILED_INSTRUCTION = Pattern.compile("^ > \\[(?<stage>[^\\]]{1,100})\\] (?<instruction>.{1,300}?):?$");
    private static final Pattern DOCKERFILE_LINE = Pattern.compile("^(?<path>\\S{0,300}?Dockerfile[\\w.-]{0,50}):(?<line>\\d{1,6})$");
    private static final Pattern NOT_FOUND = Pattern.compile("\"(?<file>[^\"]{1,300})\": not found");

    private static final Pattern MISSING_FILE = Pattern.compile(
            "failed to compute cache key|failed to calculate checksum|\"[^\"]{1,300}\": not found|(?:COPY|ADD) failed"
                    + "|failed to read dockerfile|no such file or directory", Pattern.CASE_INSENSITIVE);
    private static final Pattern MISSING_IMAGE = Pattern.compile(
            "pull access denied|manifest unknown|not found: manifest|failed to resolve source metadata|repository does not exist",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "docker";
    }

    @Override
    public int signature(StepLog step) {
        if (step.contains(STRONG_SIGNATURE) || step.name() != null && DOCKER_COMMAND.matcher(step.name()).find()) {
            return 2;
        }
        return step.contains(WEAK_SIGNATURE) ? 1 : 0;
    }

    @Override
    public Optional<Finding> parse(StepLog step) {
        List<LogLine> errors = new ArrayList<>();
        errors.addAll(step.matching(STEP_ERROR));
        errors.addAll(step.matching(FINAL_ERROR));
        errors.addAll(step.matching(CLASSIC_ERROR));
        if (errors.isEmpty()) {
            return Optional.empty();
        }
        int first = errors.stream().mapToInt(LogLine::number).min().orElseThrow();
        int last = errors.stream().mapToInt(LogLine::number).max().orElseThrow();
        String message = message(step);
        String instruction = step.find(FAILED_INSTRUCTION)
                .map(matcher -> "[" + matcher.group("stage") + "] " + matcher.group("instruction").strip())
                .orElse(null);

        Matcher notFound = NOT_FOUND.matcher(message);
        String problem = notFound.find() ? "\"" + notFound.group("file") + "\" not found in the build context" : message;
        String summary = instruction == null ? "Docker build failed: " + problem : instruction + ": " + problem;

        List<CodeReference> references = step.matching(DOCKERFILE_LINE).stream()
                .map(line -> DOCKERFILE_LINE.matcher(line.content()))
                .filter(Matcher::find)
                .map(matcher -> CodeReferences.of(matcher.group("path"), matcher.group("line")))
                .toList();
        return Optional.of(new Finding(category(message), summary, first, last,
                List.of(instruction == null ? "" : instruction, problem), CodeReferences.merge(references)));
    }

    /** BuildKit's final "failed to solve" line is the most complete; fall back to the step's own error. */
    private static String message(StepLog step) {
        return step.find(FINAL_ERROR).map(matcher -> matcher.group("message"))
                .or(() -> step.find(STEP_ERROR).map(matcher -> matcher.group("message")))
                .or(() -> step.find(CLASSIC_ERROR).map(matcher -> matcher.group("message")))
                .orElseThrow()
                .strip();
    }

    /** A RUN instruction that failed is only as clear as its own output, so that stays UNKNOWN here. */
    private static FailureCategory category(String message) {
        if (MISSING_FILE.matcher(message).find()) {
            return FailureCategory.CONFIG_ERROR;
        }
        if (MISSING_IMAGE.matcher(message).find()) {
            return FailureCategory.DEPENDENCY;
        }
        return FailureCategory.UNKNOWN;
    }
}
