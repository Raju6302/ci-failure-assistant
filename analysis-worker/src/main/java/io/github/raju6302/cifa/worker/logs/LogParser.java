package io.github.raju6302.cifa.worker.logs;

import java.util.Optional;

/**
 * Recognizes one tool's output (Maven, pytest, …) in a failing step.
 *
 * <p>Implementations must treat the log as untrusted: precompiled patterns, bounded
 * quantifiers, no nested repetition.
 */
interface LogParser {

    /** Short id stored with the analysis, e.g. {@code maven}. */
    String name();

    /**
     * How clearly the step looks like this tool's output: 0 = not at all, 1 = some hints,
     * 2 = unmistakable. The analyzer tries parsers from the highest score down.
     */
    int signature(StepLog step);

    /** The failure, if this parser can name it. */
    Optional<Finding> parse(StepLog step);
}
