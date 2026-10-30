package io.github.raju6302.cifa.worker.diagnosis;

/**
 * The agent couldn't produce a diagnosis. {@link #isRetryable()} means trying the same
 * run again later can help (rate limits, overloaded or unreachable API); otherwise the
 * caller falls back to the rule-based diagnosis straight away.
 */
public class AgentException extends RuntimeException {

    private final boolean retryable;

    AgentException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    static AgentException permanent(String message) {
        return new AgentException(message, false, null);
    }

    public boolean isRetryable() {
        return retryable;
    }
}
