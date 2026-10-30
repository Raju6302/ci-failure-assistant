package io.github.raju6302.cifa.common;

/**
 * Names shared by the producer (api-service) and consumer (analysis-worker) of the
 * run ingestion queue. Phase 1 uses a Redis Stream; Phase 9 moves to AWS SQS.
 */
public final class RunQueue {

    /** Redis Stream that holds one entry per failed run to ingest. */
    public static final String STREAM = "cifa:runs:ingest";

    /** Consumer group; every worker instance joins it so each entry is handled once. */
    public static final String GROUP = "ingest-workers";

    /** Stream entry field holding the internal {@code workflow_run.id}. */
    public static final String RUN_ID_FIELD = "workflowRunId";

    private RunQueue() {
    }
}
