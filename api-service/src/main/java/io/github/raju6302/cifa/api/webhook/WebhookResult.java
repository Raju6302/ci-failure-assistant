package io.github.raju6302.cifa.api.webhook;

/** What happened to a webhook delivery; returned to GitHub so deliveries are easy to debug. */
public enum WebhookResult {
    /** A new failed run was stored and queued. */
    ACCEPTED,
    /** Already seen (same delivery, or same run attempt from another delivery). */
    DUPLICATE,
    /** Valid but not interesting, e.g. a successful run or another event type. */
    IGNORED
}
