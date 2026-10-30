package io.github.raju6302.cifa.worker.queue;

import io.github.raju6302.cifa.common.RunQueue;
import io.github.raju6302.cifa.worker.diagnosis.DiagnosisService;
import io.github.raju6302.cifa.worker.ingest.IngestionProperties;
import io.github.raju6302.cifa.worker.ingest.RunIngestionService;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The worker's two pipeline stages, each a consumer group on its own stream:
 * ingestion (fetch jobs and logs, analyze them) feeds diagnosis (agent + RAG).
 */
@Component
class PipelineConsumers implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PipelineConsumers.class);
    /** How long an ingested run may wait before we assume its diagnosis entry was lost. */
    private static final Duration DIAGNOSIS_QUEUE_GRACE = Duration.ofMinutes(5);

    private final StreamConsumer ingestion;
    private final StreamConsumer diagnosis;
    private final DiagnosisQueue diagnosisQueue;
    private final DiagnosisService diagnosisService;
    private volatile boolean running;

    PipelineConsumers(RedisConnectionFactory connectionFactory, StringRedisTemplate redis, IngestionProperties properties,
                      RunIngestionService ingestionService, DiagnosisService diagnosisService,
                      DiagnosisQueue diagnosisQueue) {
        this.diagnosisQueue = diagnosisQueue;
        this.diagnosisService = diagnosisService;
        this.ingestion = new StreamConsumer(RunQueue.STREAM, RunQueue.GROUP, connectionFactory, redis.opsForStream(),
                properties.reclaimIdle(), runId -> {
                    // Ingestion retries go through the api-service relay, so the entry is always acknowledged.
                    ingestionService.ingest(runId);
                    if (diagnosisService.isReadyForDiagnosis(runId)) {
                        diagnosisQueue.publish(runId);
                    }
                    return true;
                });
        this.diagnosis = new StreamConsumer(RunQueue.DIAGNOSIS_STREAM, RunQueue.DIAGNOSIS_GROUP, connectionFactory,
                redis.opsForStream(), properties.reclaimIdle(),
                runId -> diagnosisService.diagnose(runId) == DiagnosisService.Outcome.DONE);
    }

    @Override
    public void start() {
        ingestion.start();
        diagnosis.start();
        running = true;
    }

    @Override
    public void stop() {
        ingestion.stop();
        diagnosis.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Scheduled(fixedDelayString = "${cifa.ingestion.reclaim-interval:PT1M}")
    void reclaimAbandoned() {
        ingestion.reclaimAbandoned();
        diagnosis.reclaimAbandoned();
    }

    /** Re-queues ingested runs whose diagnosis entry never made it onto the stream. */
    @Scheduled(fixedDelayString = "${cifa.ingestion.reclaim-interval:PT1M}")
    void requeueWaitingRuns() {
        List<Long> waiting = diagnosisService.runsMissingFromQueue(DIAGNOSIS_QUEUE_GRACE);
        for (long runId : waiting) {
            diagnosisQueue.publish(runId);
        }
        if (!waiting.isEmpty()) {
            log.info("Re-queued {} ingested runs for diagnosis", waiting.size());
        }
    }
}
