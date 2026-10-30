package io.github.raju6302.cifa.worker.ingest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code cifa.ingestion.*}
 *
 * @param maxAttempts     how many times a run is tried before it is marked INGEST_FAILED
 * @param reclaimIdle     a queue entry unacknowledged this long is assumed lost (worker crashed)
 * @param reclaimInterval how often to look for such entries
 */
@ConfigurationProperties("cifa.ingestion")
public record IngestionProperties(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("PT2M") Duration reclaimIdle,
        @DefaultValue("PT1M") Duration reclaimInterval) {
}
