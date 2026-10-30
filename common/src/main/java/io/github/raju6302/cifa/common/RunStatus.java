package io.github.raju6302.cifa.common;

/**
 * Where a failed workflow run is in the pipeline.
 *
 * <pre>
 * RECEIVED ──► QUEUED ──► INGESTING ──► INGESTED
 *    ▲  (relay re-queues      │
 *    │   if the queue was     └──► INGEST_FAILED (after max attempts)
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
    /** Jobs and logs are stored. */
    INGESTED,
    /** Gave up after repeated errors; see the run's error message. */
    INGEST_FAILED
}
