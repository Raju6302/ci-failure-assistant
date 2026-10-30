package io.github.raju6302.cifa.api.queue;

import io.github.raju6302.cifa.common.RunQueue;
import java.util.Map;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Puts run ids on the ingestion stream. */
@Component
class RunPublisher {

    private final StringRedisTemplate redis;

    RunPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    void publish(long runId) {
        redis.opsForStream().add(StreamRecords
                .string(Map.of(RunQueue.RUN_ID_FIELD, Long.toString(runId)))
                .withStreamKey(RunQueue.STREAM));
    }
}
