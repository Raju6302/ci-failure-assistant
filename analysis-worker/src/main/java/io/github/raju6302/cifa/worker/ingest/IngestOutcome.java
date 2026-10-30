package io.github.raju6302.cifa.worker.ingest;

/** Result of one ingestion attempt; either way the queue entry is acknowledged. */
public enum IngestOutcome {
    /** Stored, gave up for good, or nothing to do. */
    DONE,
    /** Failed for a temporary reason; the run went back to RECEIVED and the relay will re-queue it. */
    RETRY
}
