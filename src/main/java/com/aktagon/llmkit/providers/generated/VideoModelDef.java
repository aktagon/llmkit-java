//

package com.aktagon.llmkit.providers.generated;

public record VideoModelDef(
        String modelId,
        String label,
        boolean supportsImageToVideo,
        int maxDurationSeconds,
        String outputMime,
        java.util.List<String> resolutions,
        /**/
        int maxInputImages) {}
