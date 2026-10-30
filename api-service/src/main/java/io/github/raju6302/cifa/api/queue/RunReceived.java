package io.github.raju6302.cifa.api.queue;

/** Published inside the webhook transaction; handled once it commits. */
public record RunReceived(long workflowRunId) {
}
