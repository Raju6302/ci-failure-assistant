package io.github.raju6302.cifa.worker.logs;

import io.github.raju6302.cifa.github.model.CheckRunAnnotation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Rule-based analysis of a failed job: finds the failing step, lets the best-matching parser
 * name the error, classifies it and fingerprints it. No AI involved; it is fast and
 * deterministic, so it runs during ingestion, and its result is what the agent starts from
 * in Phase 4.
 */
@Component
public class FailureAnalyzer {

    static final String TIMEOUT_PARSER = "timeout";
    static final String ANNOTATIONS_PARSER = "annotations";
    private static final int MAX_ANNOTATION_LINES = 20;

    private final List<LogParser> parsers = List.of(
            new MavenParser(), new GradleParser(), new NodeParser(), new PythonParser(), new DockerParser());
    private final GenericParser fallback = new GenericParser();

    public FailureAnalysis analyze(JobEvidence evidence) {
        boolean timedOut = JobTimeouts.timedOut(evidence.conclusion(), evidence.annotations());
        if (!evidence.hasLog()) {
            return fromAnnotations(evidence, timedOut);
        }
        List<StepLog> steps = LogSteps.split(LogNormalizer.normalize(evidence.log()));
        Optional<StepLog> failing = LogSteps.failing(steps, evidence.failedSteps());
        if (failing.isEmpty()) {
            return fromAnnotations(evidence, timedOut);
        }
        StepLog step = failing.get();
        String stepName = LogSteps.displayName(step, evidence.failedSteps());
        List<CheckRunAnnotation> failureAnnotations = evidence.failureAnnotations();
        List<String> annotationMessages = messages(failureAnnotations);

        String parser;
        Finding finding;
        if (timedOut) {
            parser = TIMEOUT_PARSER;
            Finding tail = GenericParser.tail(step);
            finding = new Finding(FailureCategory.INFRASTRUCTURE, timeoutSummary(evidence, stepName, tail), tail.firstLine(),
                    tail.lastLine(), List.of("timeout", stepName == null ? "" : stepName), tail.codeReferences());
        } else {
            ParserFinding best = parse(step);
            parser = best.parser();
            finding = best.finding();
        }

        FailureCategory category = FailureClassifier.classify(finding.category(), step, annotationMessages, timedOut);
        String summary = finding.specific() ? finding.summary() : betterAnnotation(failureAnnotations).orElse(finding.summary());
        Excerpts.Excerpt excerpt = Excerpts.of(step, finding.firstLine(), finding.lastLine());
        List<CodeReference> references = new ArrayList<>(finding.codeReferences());
        references.addAll(annotationReferences(failureAnnotations));
        return new FailureAnalysis(category, stepName, summary, excerpt.text(), excerpt.startLine(), excerpt.endLine(),
                CodeReferences.merge(references), Fingerprints.of(category, finding.keyLines()), parser);
    }

    private record ParserFinding(String parser, Finding finding) {
    }

    /** Tries parsers from the clearest tool signature down; the generic parser always answers. */
    private ParserFinding parse(StepLog step) {
        List<LogParser> candidates = parsers.stream()
                .filter(parser -> parser.signature(step) > 0)
                .sorted(Comparator.comparingInt((LogParser parser) -> parser.signature(step)).reversed())
                .toList();
        for (LogParser parser : candidates) {
            Optional<Finding> finding = parser.parse(step);
            if (finding.isPresent()) {
                return new ParserFinding(parser.name(), finding.get());
            }
        }
        return new ParserFinding(fallback.name(), fallback.parse(step).orElseGet(() -> GenericParser.tail(step)));
    }

    /** No log (expired, or GitHub never had one): annotations are all there is. */
    private FailureAnalysis fromAnnotations(JobEvidence evidence, boolean timedOut) {
        String stepName = LogSteps.displayName(null, evidence.failedSteps());
        List<CheckRunAnnotation> annotations = evidence.failureAnnotations();
        if (annotations.isEmpty() && !timedOut) {
            String summary = "No log or error annotations available"
                    + (stepName == null ? "" : "; failed step: \"" + stepName + "\"");
            return new FailureAnalysis(FailureCategory.UNKNOWN, stepName, summary, "", null, null, List.of(),
                    Fingerprints.of(FailureCategory.UNKNOWN, List.of("no evidence", stepName == null ? "" : stepName)), "none");
        }
        List<String> messages = messages(annotations);
        FailureCategory category = FailureClassifier.classify(FailureCategory.UNKNOWN, null, messages, timedOut);
        String summary = timedOut
                ? timeoutSummary(evidence, stepName, null)
                : betterAnnotation(annotations).orElse(annotations.getFirst().message());
        String excerpt = annotations.stream()
                .limit(MAX_ANNOTATION_LINES)
                .map(annotation -> location(annotation) + annotation.message())
                .collect(Collectors.joining("\n"));
        return new FailureAnalysis(category, stepName, Finding.oneLine(summary), excerpt, null, null,
                CodeReferences.merge(annotationReferences(annotations)), Fingerprints.of(category, messages), ANNOTATIONS_PARSER);
    }

    /**
     * "Timed out after 1m0s in "Build and test"; last output: Waiting for payments-service…".
     * What the step printed last is usually what it was stuck on.
     */
    private static String timeoutSummary(JobEvidence evidence, String stepName, Finding tail) {
        String limit = JobTimeouts.limit(evidence.annotations()).map(value -> " after " + value).orElse("");
        String step = stepName == null ? "" : " in \"" + stepName + "\"";
        String lastOutput = tail == null || tail.keyLines().isEmpty() ? "" : "; last output: " + tail.keyLines().getFirst();
        return "Job timed out" + limit + step + lastOutput;
    }

    /**
     * A failure annotation that says more than "Process completed with exit code 1": one with a
     * file (compiler problem matchers, test reporters) or at least a message of its own.
     */
    private static Optional<String> betterAnnotation(List<CheckRunAnnotation> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.message() != null && !GenericParser.isAftermath(annotation.message()))
                .min(Comparator.comparing(annotation -> !annotation.hasFile()))
                .map(annotation -> Finding.oneLine(location(annotation) + annotation.message()));
    }

    private static List<CodeReference> annotationReferences(List<CheckRunAnnotation> annotations) {
        return annotations.stream()
                .filter(CheckRunAnnotation::hasFile)
                .map(annotation -> CodeReferences.of(annotation.path(), annotation.startLine()))
                .toList();
    }

    private static String location(CheckRunAnnotation annotation) {
        if (!annotation.hasFile()) {
            return "";
        }
        return annotation.startLine() == null ? annotation.path() + ": " : annotation.path() + ":" + annotation.startLine() + ": ";
    }

    private static List<String> messages(List<CheckRunAnnotation> annotations) {
        return annotations.stream().map(CheckRunAnnotation::message).filter(message -> message != null && !message.isBlank()).toList();
    }
}
