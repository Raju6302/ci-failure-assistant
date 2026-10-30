package io.github.raju6302.cifa.common;

/**
 * Names of the Redis Streams that connect the pipeline stages. Phase 9 moves them to AWS SQS.
 *
 * <ul>
 *   <li>Ingestion: api-service → analysis-worker. One entry per failed run to ingest.</li>
 *   <li>Diagnosis: analysis-worker → analysis-worker. One entry per ingested run. A separate
 *       stage because LLM calls are slow and are retried on their own schedule.</li>
 * </ul>
 */
public final class RunQueue {

    /** Redis Stream that holds one entry per failed run to ingest. */
    public static final String STREAM = "cifa:runs:ingest";

    /** Consumer group; every worker instance joins it so each entry is handled once. */
    public static final String GROUP = "ingest-workers";

    /** Redis Stream that holds one entry per ingested run to diagnose. */
    public static final String DIAGNOSIS_STREAM = "cifa:runs:diagnose";

    /** Consumer group for the diagnosis stage. */
    public static final String DIAGNOSIS_GROUP = "diagnosis-workers";

    /** Stream entry field holding the internal {@code workflow_run.id}. */
    public static final String RUN_ID_FIELD = "workflowRunId";

    private RunQueue() {
    }
}
