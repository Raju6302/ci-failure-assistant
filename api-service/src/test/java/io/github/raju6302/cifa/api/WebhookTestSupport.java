package io.github.raju6302.cifa.api;

import io.github.raju6302.cifa.common.RunQueue;
import io.github.raju6302.cifa.github.webhook.WebhookSignatureVerifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Shared helpers for tests that send webhooks and inspect the database and queue. */
public final class WebhookTestSupport {

    public static final String SECRET = "test-webhook-secret";

    /** Test property that configures {@link #SECRET} as the webhook secret. */
    public static final String SECRET_PROPERTY = "cifa.webhook.secret=" + SECRET;

    private WebhookTestSupport() {
    }

    public static String failedRunPayload() {
        try {
            return new ClassPathResource("webhooks/workflow_run_failed.json").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String sign(String body) {
        return new WebhookSignatureVerifier(SECRET).signatureFor(body.getBytes(StandardCharsets.UTF_8));
    }

    public static void reset(JdbcClient jdbc, StringRedisTemplate redis) {
        jdbc.sql("TRUNCATE failed_job, workflow_run, repository, webhook_delivery RESTART IDENTITY CASCADE").update();
        redis.delete(RunQueue.STREAM);
    }

    public static long queuedEntries(StringRedisTemplate redis) {
        Long size = redis.opsForStream().size(RunQueue.STREAM);
        return size == null ? 0 : size;
    }
}
