package io.github.raju6302.cifa.api.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code cifa.webhook.secret}: the shared secret configured on the GitHub App or repo webhook. */
@ConfigurationProperties("cifa.webhook")
public record WebhookProperties(String secret) {
}
