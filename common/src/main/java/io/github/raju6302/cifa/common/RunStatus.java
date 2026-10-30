package io.github.raju6302.cifa.common;

/**
 * Where a failed workflow run is in the pipeline.
 *
 * <pre>
 * RECEIVED ──► QUEUED ──► INGESTING ──► INGESTED ──► DIAGNOSING ──► DIAGNOSED
 *    ▲  (relay re-queues      │                          │
 *    │   if the queue was     └──► INGEST_FAILED         └──► DIAGNOSIS_FAILED
 *    │   down)
 * </pre>
 */
public enum RunStatus {
    /** Webhook stored; not yet on the queue. */
    RECEIVED,
    /** On the queue, waiting for a worker. */
    QUEUED,
    /** A worker is fetching jobs and logs. */
    INGESTING,
    /** Jobs, logs and their rule-based analysis are stored; waiting for diagnosis. */
    INGESTED,
    /** Gave up after repeated errors; see the run's error message. */
    INGEST_FAILED,
    /** The diagnosis agent is working on the run's failed jobs. */
    DIAGNOSING,
    /** Every failed job has a diagnosis (from the agent, or the rule-based fallback). */
    DIAGNOSED,
    /** Diagnosis could not be produced at all; see the run's error message. */
    DIAGNOSIS_FAILED
}
