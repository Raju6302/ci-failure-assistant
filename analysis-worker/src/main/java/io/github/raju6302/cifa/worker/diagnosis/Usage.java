package io.github.raju6302.cifa.worker.diagnosis;

/** Tokens, cost and time spent on one diagnosis. */
public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                    double costUsd, long latencyMs) {

    public static final Usage NONE = new Usage(0, 0, 0, 0, 0, 0);

    public long totalTokens() {
        return inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens;
    }

    Usage plus(long input, long output, long cacheRead, long cacheWrite, DiagnosisProperties.Pricing price) {
        double cost = (input * price.inputPerMillion() + output * price.outputPerMillion()
                + cacheRead * price.cacheReadPerMillion() + cacheWrite * price.cacheWritePerMillion()) / 1_000_000;
        return new Usage(inputTokens + input, outputTokens + output, cacheReadTokens + cacheRead,
                cacheWriteTokens + cacheWrite, costUsd + cost, latencyMs);
    }

    Usage withLatency(long millis) {
        return new Usage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, costUsd, millis);
    }
}
