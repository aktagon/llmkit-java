//

package com.aktagon.llmkit.providers.generated;

public record MusicModelDef(
        String modelId,
        String label,
        boolean supportsLyrics,
        int maxDurationSeconds,
        String outputMime,
        /**/
        int sampleRateHz,
        java.util.List<String> availableOutputFormats) {}
