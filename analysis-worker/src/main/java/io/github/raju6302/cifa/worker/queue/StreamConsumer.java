package io.github.raju6302.cifa.worker.queue;

import io.github.raju6302.cifa.common.RunQueue;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.function.LongPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

/**
 * Reads run ids from one Redis Stream as part of a consumer group, so several workers share
 * the load and each entry is handled once. An entry is acknowledged only when the handler
 * says so; entries left unacknowledged (the handler asked to retry, or the worker crashed)
 * are reclaimed by {@link #reclaimAbandoned()}.
 */
final class StreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(StreamConsumer.class);

    private final String stream;
    private final String group;
    private final StreamOperations<String, String, String> streams;
    private final LongPredicate handler;
    private final Duration reclaimIdle;
    private final String consumerName = consumerName();
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    /** @param handler returns true when the entry should be acknowledged */
    StreamConsumer(String stream, String group, RedisConnectionFactory connectionFactory,
                   StreamOperations<String, String, String> streams, Duration reclaimIdle, LongPredicate handler) {
        this.stream = stream;
        this.group = group;
        this.streams = streams;
        this.reclaimIdle = reclaimIdle;
        this.handler = handler;
        this.container = StreamMessageListenerContainer.create(connectionFactory,
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .errorHandler(e -> log.error("Error reading {}", stream, e))
                        .build());
    }

    void start() {
        createGroupIfMissing();
        container.receive(Consumer.from(group, consumerName), StreamOffset.create(stream, ReadOffset.lastConsumed()),
                this::handle);
        container.start();
        log.info("Consuming {} as {}", stream, consumerName);
    }

    void stop() {
        container.stop();
    }

    /** Takes over entries that have been pending longer than the reclaim timeout. */
    void reclaimAbandoned() {
        for (PendingMessage pending : streams.pending(stream, group, Range.unbounded(), 50)) {
            if (pending.getElapsedTimeSinceLastDelivery().compareTo(reclaimIdle) < 0) {
                continue;
            }
            List<MapRecord<String, String, String>> claimed =
                    streams.claim(stream, group, consumerName, reclaimIdle, pending.getId());
            claimed.forEach(record -> {
                log.info("Reclaimed {} entry {} from {}", stream, record.getId(), pending.getConsumerName());
                handle(record);
            });
        }
    }

    void handle(MapRecord<String, String, String> record) {
        long runId;
        try {
            runId = Long.parseLong(record.getValue().get(RunQueue.RUN_ID_FIELD));
        } catch (NumberFormatException e) {
            log.error("Dropping malformed {} entry {}: {}", stream, record.getId(), record.getValue());
            acknowledge(record);
            return;
        }
        // Any exception escaping here (e.g. database down) leaves the entry pending for reclaim.
        if (handler.test(runId)) {
            acknowledge(record);
        }
    }

    private void acknowledge(MapRecord<String, String, String> record) {
        streams.acknowledge(stream, group, record.getId());
    }

    private void createGroupIfMissing() {
        try {
            streams.createGroup(stream, ReadOffset.from("0-0"), group);
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
