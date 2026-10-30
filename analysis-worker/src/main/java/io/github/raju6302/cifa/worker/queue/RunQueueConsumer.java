package io.github.raju6302.cifa.worker.queue;

import io.github.raju6302.cifa.common.RunQueue;
import io.github.raju6302.cifa.worker.ingest.IngestionProperties;
import io.github.raju6302.cifa.worker.ingest.RunIngestionService;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads run ids from the Redis Stream as part of a consumer group, so several workers
 * share the load and each entry is handled once. An entry is acknowledged only after it
 * is handled; entries left unacknowledged by a crashed worker are reclaimed.
 */
@Component
class RunQueueConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunQueueConsumer.class);

    private final StreamOperations<String, String, String> streams;
    private final RunIngestionService ingestion;
    private final IngestionProperties properties;
    private final String consumerName = consumerName();
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private volatile boolean running;

    RunQueueConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redis,
                     RunIngestionService ingestion, IngestionProperties properties) {
        this.streams = redis.opsForStream();
        this.ingestion = ingestion;
        this.properties = properties;
        this.container = StreamMessageListenerContainer.create(connectionFactory,
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .errorHandler(e -> log.error("Error reading the ingestion queue", e))
                        .build());
    }

    @Override
    public void start() {
        createGroupIfMissing();
        container.receive(Consumer.from(RunQueue.GROUP, consumerName),
                StreamOffset.create(RunQueue.STREAM, ReadOffset.lastConsumed()),
                this::handle);
        container.start();
        running = true;
        log.info("Consuming {} as {}", RunQueue.STREAM, consumerName);
    }

    @Override
    public void stop() {
        container.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Takes over entries another (probably dead) consumer received but never acknowledged. */
    @Scheduled(fixedDelayString = "${cifa.ingestion.reclaim-interval:PT1M}")
    void reclaimAbandoned() {
        for (PendingMessage pending : streams.pending(RunQueue.STREAM, RunQueue.GROUP, Range.unbounded(), 50)) {
            if (pending.getElapsedTimeSinceLastDelivery().compareTo(properties.reclaimIdle()) < 0) {
                continue;
            }
            List<MapRecord<String, String, String>> claimed = streams.claim(
                    RunQueue.STREAM, RunQueue.GROUP, consumerName, properties.reclaimIdle(), pending.getId());
            claimed.forEach(record -> {
                log.info("Reclaimed queue entry {} from {}", record.getId(), pending.getConsumerName());
                handle(record);
            });
        }
    }

    void handle(MapRecord<String, String, String> record) {
        String value = record.getValue().get(RunQueue.RUN_ID_FIELD);
        long runId;
        try {
            runId = Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.error("Dropping malformed queue entry {}: {}", record.getId(), record.getValue());
            acknowledge(record);
            return;
        }
        // Any exception escaping here (e.g. database down) leaves the entry pending for reclaim.
        ingestion.ingest(runId);
        acknowledge(record);
    }

    private void acknowledge(MapRecord<String, String, String> record) {
        streams.acknowledge(RunQueue.STREAM, RunQueue.GROUP, record.getId());
    }

    private void createGroupIfMissing() {
        try {
            streams.createGroup(RunQueue.STREAM, ReadOffset.from("0-0"), RunQueue.GROUP);
        } catch (RedisSystemException e) {
            if (!String.valueOf(e.getMostSpecificCause().getMessage()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private static String consumerName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "worker";
        }
        return host + "-" + ProcessHandle.current().pid();
    }
}
