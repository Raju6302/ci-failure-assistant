package io.github.raju6302.cifa.api.webhook;

import io.github.raju6302.cifa.api.queue.RunReceived;
import io.github.raju6302.cifa.api.runs.RunStore;
import io.github.raju6302.cifa.github.webhook.WorkflowRunEvent;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores failed runs from {@code workflow_run} webhooks. Queueing happens after commit. */
@Service
public class WorkflowRunWebhookHandler {

    private final RunStore store;
    private final ApplicationEventPublisher events;

    WorkflowRunWebhookHandler(RunStore store, ApplicationEventPublisher events) {
        this.store = store;
        this.events = events;
    }

    @Transactional
    public WebhookResult handle(String deliveryId, WorkflowRunEvent event) {
        if (!store.recordDelivery(deliveryId, "workflow_run")) {
            return WebhookResult.DUPLICATE;
        }
        if (!event.isCompletedFailure() || event.repository() == null) {
            return WebhookResult.IGNORED;
        }
        long repositoryId = store.upsertRepository(event.repository(), event.installationId());
        Optional<Long> runId = store.insertRun(repositoryId, event.workflowRun());
        if (runId.isEmpty()) {
            return WebhookResult.DUPLICATE;
        }
        events.publishEvent(new RunReceived(runId.get()));
        return WebhookResult.ACCEPTED;
    }
}
