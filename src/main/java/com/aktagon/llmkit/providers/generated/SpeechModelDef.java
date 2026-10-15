// Code generated — DO NOT EDIT.

package com.aktagon.llmkit.providers.generated;

public record SpeechModelDef(
        String modelId,
        String label,
        String outputMime,
        /** ADR-037 advisory fact (queryable, never gated); 0 when unverified. */
        int sampleRateHz,
        /** Speakers a multi-speaker request declares; 0 = one voice only (BUG-091). */
        int maxSpeakers,
        /** Whether a style (style, addStyledLine) is sent to this model (BUG-091). */
        boolean supportsSpeechStyle) {}
