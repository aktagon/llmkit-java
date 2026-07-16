package com.aktagon.llmkit;

import java.util.ArrayList;
import java.util.List;

/**
 * The accumulated generation parameters carried by the {@link Text} builder and
 * applied to the request body by {@link RequestBuilder} (mirrors Swift's
 * {@code PromptOptions} / Rust's {@code PromptOptions}). Package-private — the
 * public surface is the builder chain. Mutable; {@link Text} clones it on each
 * chain step so builders stay immutable value-like.
 */
final class PromptOptions {
    Integer maxTokens;
    Double temperature;
    Double topP;
    Integer topK;
    Long seed;
    Double frequencyPenalty;
    Double presencePenalty;
    Integer thinkingBudget;
    String reasoningEffort;
    List<String> stopSequences = new ArrayList<>();
    List<SafetySetting> safetySettings = new ArrayList<>();
    /** The chat-protocol opt-in token (ADR-055), e.g. {@code "responses"}. Empty = default. */
    String proto = "";
    /** A JSON-Schema string for structured output, or null. */
    String schema;

    PromptOptions() {}

    /** A deep-enough copy for clone-on-chain (lists copied, scalars shared). */
    PromptOptions copy() {
        PromptOptions o = new PromptOptions();
        o.maxTokens = maxTokens;
        o.temperature = temperature;
        o.topP = topP;
        o.topK = topK;
        o.seed = seed;
        o.frequencyPenalty = frequencyPenalty;
        o.presencePenalty = presencePenalty;
        o.thinkingBudget = thinkingBudget;
        o.reasoningEffort = reasoningEffort;
        o.stopSequences = new ArrayList<>(stopSequences);
        o.safetySettings = new ArrayList<>(safetySettings);
        o.proto = proto;
        o.schema = schema;
        return o;
    }
}
