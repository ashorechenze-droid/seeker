package com.simplerag.model;

/**
 * Real token consumption as reported by the provider's {@code usage} object.
 *
 * <p>This is the billed figure, not a local estimate: it is the only number that can be trusted to
 * match what the provider charged. {@link #UNKNOWN} means the provider did not report usage — for
 * streaming responses that happens when the endpoint ignores {@code stream_options.include_usage}.
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
    public static final TokenUsage UNKNOWN = new TokenUsage(0, 0, 0);

    public TokenUsage {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        totalTokens = Math.max(0, totalTokens);
        // Some OpenAI-compatible relays omit total_tokens while still reporting the two halves.
        if (totalTokens == 0) totalTokens = promptTokens + completionTokens;
    }

    public boolean known() {
        return totalTokens > 0;
    }

    /** Accumulates consumption across the several calls one agentic turn makes. */
    public TokenUsage plus(TokenUsage other) {
        if (other == null || !other.known()) return this;
        if (!known()) return other;
        return new TokenUsage(promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens);
    }
}
