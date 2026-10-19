// Code generated — DO NOT EDIT.

package com.aktagon.llmkit.providers.generated;

public record SpeechModelDef(
        String modelId,
        String label,
        String outputMime,
        /** ADR-037 advisory fact (queryable, never gated); 0 when unverified. */
        int sampleRateHz,
        /** Speakers the model takes (1 or 2); null = unknown, which sends (decision 009). */
        Integer maxSpeakers,
        /** Whether the provider accepts a style; null = unknown, only false blocks (decision 009). */
        Boolean supportsSpeechStyle) {}
