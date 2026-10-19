// Code generated — DO NOT EDIT.

package com.aktagon.llmkit.providers.generated;

public record VideoModelDef(
        String modelId,
        String label,
        boolean supportsImageToVideo,
        /** Whether lastFrame is accepted; false = rejected before send (BUG-089). */
        boolean supportsLastFrame,
        /** Whether addReferenceImage is accepted; false = rejected before send. */
        boolean supportsReferenceImages,
        int maxDurationSeconds,
        String outputMime,
        java.util.List<String> resolutions,
        /** Accepted aspect ratios (e.g. "16:9"); empty = no fixed set. */
        java.util.List<String> aspectRatios,
        /** 0 = no llmkit limit, the provider decides volume (BUG-011). */
        int maxInputImages) {}
