package io.github.raju6302.cifa.api.queue;

import io.github.raju6302.cifa.api.runs.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Queues runs once their webhook transaction has committed, so a worker never picks up
 * a run that isn't in the database yet. If Redis is down, the run stays RECEIVED and
 * {@link PendingRunRelay} tries again later. GitHub does not redeliver webhooks by itself.
 */
@Component
class RunDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RunDispatcher.class);

    private final RunPublisher publisher;
    private final RunStore store;

    RunDispatcher(RunPublisher publisher, RunStore store) {
        this.publisher = publisher;
        this.store = store;
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onRunReceived(RunReceived event) {
        dispatch(event.workflowRunId());
    }

    boolean dispatch(long runId) {
        try {
            publisher.publish(runId);
        } catch (RuntimeException e) {
            log.warn("Could not queue run {}; the relay will retry", runId, e);
            return false;
        }
        store.markQueued(runId);
        return true;
    }
}
