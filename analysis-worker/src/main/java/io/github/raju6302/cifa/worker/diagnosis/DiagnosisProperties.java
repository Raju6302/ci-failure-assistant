package io.github.raju6302.cifa.worker.diagnosis;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code cifa.diagnosis.*}: how the diagnosis agent calls Claude and its tools.
 *
 * @param agentEnabled       false = rule-based diagnoses only (no LLM calls)
 * @param model              Claude model id
 * @param effort             output effort: low, medium, high, xhigh or max
 * @param maxSteps           model calls per job before giving up
 * @param maxTokensPerTurn   max_tokens for each model call
 * @param tokenBudget        input + output tokens per job; when reached the agent is told to submit
 * @param serverSideFallback retry refused requests on Anthropic's recommended fallback model
 * @param maxAttempts        attempts per run on temporary errors before falling back to rules
 * @param mcp                where the agent's tools are served
 * @param pricing            USD per million tokens, for cost tracking
 */
@ConfigurationProperties("cifa.diagnosis")
public record DiagnosisProperties(
        @DefaultValue("true") boolean agentEnabled,
        @DefaultValue("claude-opus-5-5") String model,
        @DefaultValue("high") String effort,
        @DefaultValue("15") int maxSteps,
        @DefaultValue("16000") long maxTokensPerTurn,
        @DefaultValue("400000") long tokenBudget,
        @DefaultValue("true") boolean serverSideFallback,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue Mcp mcp,
        @DefaultValue Pricing pricing) {

    /** @param url Streamable HTTP endpoint of mcp-github-server; @param authToken shared bearer token */
    public record Mcp(@DefaultValue("http://localhost:8082/mcp") URI url, String authToken) {
    }

    /** Defaults are Claude Opus 5.5 list prices; cache writes are 1.25x input (5-minute TTL). */
    public record Pricing(
            @DefaultValue("4.00") double inputPerMillion,
            @DefaultValue("20.00") double outputPerMillion,
            @DefaultValue("0.20") double cacheReadPerMillion,
            @DefaultValue("5.00") double cacheWritePerMillion) {
    }
}
