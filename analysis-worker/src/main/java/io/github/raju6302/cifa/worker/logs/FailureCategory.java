package io.github.raju6302.cifa.worker.logs;

/** What kind of problem failed a job. Stored as text; the V3 migration checks the same values. */
public enum FailureCategory {
    COMPILE_ERROR,
    TEST_FAILURE,
    DEPENDENCY,
    /** Only with explicit evidence in the log, such as a test tool's retry marker; see {@link FailureClassifier}. */
    FLAKY_TEST,
    /** Timeouts, lost runners, out of memory, network or registry outages. */
    INFRASTRUCTURE,
    /** Missing files, invalid workflows, unknown commands, missing settings. */
    CONFIG_ERROR,
    UNKNOWN
}
