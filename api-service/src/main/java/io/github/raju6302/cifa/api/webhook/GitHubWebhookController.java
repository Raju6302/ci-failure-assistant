package io.github.raju6302.cifa.api.webhook;

import io.github.raju6302.cifa.github.GitHubJson;
import io.github.raju6302.cifa.github.webhook.WebhookSignatureVerifier;
import io.github.raju6302.cifa.github.webhook.WorkflowRunEvent;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;

/**
 * Receives GitHub webhooks. Every request must carry a valid HMAC signature; without a
 * configured secret the endpoint refuses everything rather than trusting unsigned calls.
 */
@RestController
class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);

    private final WebhookSignatureVerifier verifier;
    private final WorkflowRunWebhookHandler workflowRuns;

    GitHubWebhookController(WebhookProperties properties, WorkflowRunWebhookHandler workflowRuns) {
        this.workflowRuns = workflowRuns;
        if (StringUtils.hasText(properties.secret())) {
            this.verifier = new WebhookSignatureVerifier(properties.secret());
        } else {
            this.verifier = null;
            log.warn("GITHUB_WEBHOOK_SECRET is not set: all webhook deliveries will be rejected");
        }
    }

    @PostMapping("/webhooks/github")
    ResponseEntity<Map<String, String>> receive(
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader("X-GitHub-Delivery") String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] body) {
        if (verifier == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "Webhook secret is not configured");
        }
        if (!verifier.isValid(body, signature)) {
            log.warn("Rejected webhook delivery {} with an invalid signature", deliveryId);
            return error(HttpStatus.UNAUTHORIZED, "Invalid signature");
        }
        WebhookResult result = switch (event) {
            case "ping" -> WebhookResult.ACCEPTED;
            case "workflow_run" -> workflowRuns.handle(deliveryId,
                    GitHubJson.mapper().readValue(body, WorkflowRunEvent.class));
            default -> WebhookResult.IGNORED;
        };
        log.info("Webhook {} ({}): {}", deliveryId, event, result);
        return ResponseEntity.accepted().body(Map.of("result", result.name().toLowerCase()));
    }

    @ExceptionHandler(JacksonException.class)
    ResponseEntity<Map<String, String>> malformedPayload(JacksonException e) {
        return error(HttpStatus.BAD_REQUEST, "Malformed payload");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
