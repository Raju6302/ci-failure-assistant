package io.github.raju6302.cifa.api.queue;

import io.github.raju6302.cifa.api.runs.RunStore;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-queues runs that were stored but not queued (for example because Redis was down). */
@Component
class PendingRunRelay {

    private static final Logger log = LoggerFactory.getLogger(PendingRunRelay.class);
    private static final int BATCH_SIZE = 100;

    private final RunStore store;
    private final RunDispatcher dispatcher;
    private final Duration minAge;

    PendingRunRelay(RunStore store, RunDispatcher dispatcher, @Value("${cifa.relay.min-age:PT30S}") Duration minAge) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.minAge = minAge;
    }

    @Scheduled(fixedDelayString = "${cifa.relay.interval:PT30S}", initialDelayString = "${cifa.relay.interval:PT30S}")
    void requeueStuckRuns() {
        List<Long> stuck = store.findStuckInReceived(minAge, BATCH_SIZE);
        long requeued = stuck.stream().filter(dispatcher::dispatch).count();
        if (!stuck.isEmpty()) {
            log.info("Relay re-queued {} of {} stuck runs", requeued, stuck.size());
        }
    }
}
