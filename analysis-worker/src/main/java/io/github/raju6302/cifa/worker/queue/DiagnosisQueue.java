package io.github.raju6302.cifa.worker.queue;

import io.github.raju6302.cifa.common.RunQueue;
import java.util.Map;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Puts ingested runs on the diagnosis stream. */
@Component
class DiagnosisQueue {

    private final StringRedisTemplate redis;

    DiagnosisQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    void publish(long runId) {
        redis.opsForStream().add(StreamRecords
                .string(Map.of(RunQueue.RUN_ID_FIELD, Long.toString(runId)))
                .withStreamKey(RunQueue.DIAGNOSIS_STREAM));
    }
}
